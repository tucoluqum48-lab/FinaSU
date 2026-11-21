#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <fcntl.h>
#include <limits.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <csoloader.h>
#include <dobby.h>
#include <plti.h>

#include "daemon.h"
#include "folkzygisk_paths.h"
#include "logging.h"
#include "zygisk_next_api.h"
#include "zn_api.h"

#define ZN_MAX_MODULES 8
#define ZN_MAX_IMAGES 8
#define ZN_HANDLE_MAGIC 0x5a4e4831u

struct zn_handle {
  uint32_t magic;
  char path[ZN_LIB_MAX];
  bool companion;
  int companion_fd;
};

struct zn_loaded {
  struct csoloader lib;
  struct zn_handle handle;
};

struct zn_image {
  void *base;
  char key[32];
};

static struct zn_loaded g_modules[ZN_MAX_MODULES];
static size_t g_module_count;

static struct plti g_plti;
static bool g_plti_ready;
static struct zn_image g_images[ZN_MAX_IMAGES];
static size_t g_image_count;

struct ZnSymbolResolver {
  struct csoloader_elf *img;
};

static const char *image_key(void *base) {
  for (size_t i = 0; i < g_image_count; ++i) {
    if (g_images[i].base == base) return g_images[i].key;
  }
  if (g_image_count >= ZN_MAX_IMAGES) return NULL;

  if (!g_plti_ready) {
    if (!plti_init(&g_plti)) return NULL;
    g_plti_ready = true;
  }

  struct zn_image *image = &g_images[g_image_count];
  snprintf(image->key, sizeof(image->key), "zn-image-%02zu", g_image_count);
  if (!plti_add_manual_lib(&g_plti, image->key, (uintptr_t)base)) return NULL;

  image->base = base;
  ++g_image_count;
  return image->key;
}

static int zn_plt_hook(void *base_addr, const char *symbol, void *hook_handler,
                       void **original) {
  if (!base_addr || !symbol || !hook_handler) return ZN_FAILED;

  const char *key = image_key(base_addr);
  if (!key) {
    LOGE("ZN: cannot hook %s: unsupported image %p", symbol, base_addr);
    return ZN_FAILED;
  }

  return plti_add_hook(&g_plti, key, symbol, hook_handler, original) ? ZN_SUCCESS : ZN_FAILED;
}

/* Very short leaf functions can be too small to relocate; the engine reports
   that as a failure rather than leaving a half-patched prologue. */
static int zn_inline_hook(void *target, void *addr, void **original) {
  if (!target || !addr) return ZN_FAILED;

  return DobbyHook(target, (dobby_dummy_func_t)addr, (dobby_dummy_func_t *)original) == RS_SUCCESS
             ? ZN_SUCCESS
             : ZN_FAILED;
}

static int zn_inline_unhook(void *target) {
  if (!target) return ZN_FAILED;

  return DobbyDestroy(target) == RS_SUCCESS ? ZN_SUCCESS : ZN_FAILED;
}

static struct ZnSymbolResolver *zn_new_symbol_resolver(const char *path, void *base_addr) {
  if (!path || path[0] == '\0') return NULL;

  struct ZnSymbolResolver *resolver = malloc(sizeof(*resolver));
  if (!resolver) return NULL;

  resolver->img = csoloader_elf_create(path, base_addr);
  if (!resolver->img) {
    free(resolver);
    return NULL;
  }

  return resolver;
}

static void zn_free_symbol_resolver(struct ZnSymbolResolver *resolver) {
  if (!resolver) return;

  csoloader_elf_destroy(resolver->img);
  free(resolver);
}

static void *zn_get_base_address(struct ZnSymbolResolver *resolver) {
  return resolver && resolver->img ? resolver->img->base : NULL;
}

typedef bool (*zn_symbol_callback)(const char *name, void *addr, size_t size, void *data);

struct symbol_scan {
  zn_symbol_callback callback;
  void *data;
  bool completed;
};

static void scan_symbols(ElfW(Sym) *symbols, size_t count, const char *strings,
                         const char *strings_end, void *base, off_t bias,
                         struct symbol_scan *scan) {
  for (size_t i = 0; i < count && scan->completed; ++i) {
    ElfW(Sym) *symbol = &symbols[i];
    if (symbol->st_name == 0 || symbol->st_value == 0) continue;
    if (symbol->st_name >= (size_t)(strings_end - strings)) continue;

    const char *name = strings + symbol->st_name;
    if (!memchr(name, '\0', (size_t)(strings_end - name))) continue;
    void *address = (void *)(uintptr_t)((uintptr_t)base + symbol->st_value - (uintptr_t)bias);
    if (!scan->callback(name, address, (size_t)symbol->st_size, scan->data))
      scan->completed = false;
  }
}

static void zn_for_each_symbol(struct ZnSymbolResolver *resolver, zn_symbol_callback callback,
                               void *data) {
  if (!resolver || !resolver->img || !callback) return;

  struct csoloader_elf *img = resolver->img;
  struct symbol_scan scan = { callback, data, true };

  if (img->symtab_start && img->symtab_count > 0 && img->symstr_offset_for_symtab != 0 &&
      img->symtab && img->section_header && img->symtab->sh_link < img->header->e_shnum) {
    ElfW(Shdr) *strings = img->section_header + img->symtab->sh_link;
    const char *start = (const char *)((uintptr_t)img->header + img->symstr_offset_for_symtab);
    scan_symbols(img->symtab_start, (size_t)img->symtab_count, start,
                 start + strings->sh_size, img->base, img->bias, &scan);
  }

  if (scan.completed && img->dynsym_start && img->strtab_start && img->dynsym && img->strtab &&
      img->dynsym->sh_entsize != 0) {
    size_t count = (size_t)(img->dynsym->sh_size / img->dynsym->sh_entsize);
    const char *start = (const char *)img->strtab_start;
    scan_symbols(img->dynsym_start, count, start, start + img->strtab->sh_size, img->base,
                 img->bias, &scan);
  }
}

static size_t symbol_size(struct csoloader_elf *img, const void *address) {
  if (!img->symtab_start || img->symtab_count == 0) return 0;

  for (size_t i = 0; i < img->symtab_count; ++i) {
    ElfW(Sym) *symbol = &img->symtab_start[i];
    if (symbol->st_size == 0) continue;

    void *candidate =
        (void *)(uintptr_t)((uintptr_t)img->base + symbol->st_value - (uintptr_t)img->bias);
    if (candidate == address) return (size_t)symbol->st_size;
  }

  return 0;
}

static void *zn_symbol_lookup(struct ZnSymbolResolver *resolver, const char *name, bool prefix,
                              size_t *size) {
  if (!resolver || !resolver->img || !name) return NULL;

  struct csoloader_elf *img = resolver->img;
  void *address = prefix ? (void *)csoloader_elf_symb_address_by_prefix(img, name)
                         : (void *)csoloader_elf_symb_address(img, name);
  if (!address) return NULL;

  if (size) *size = symbol_size(img, address);
  return address;
}


#define ZN_HYOS_MAX_MODULES 16

static struct ZygiskNextHyosModule g_hyos_modules[ZN_HYOS_MAX_MODULES];
static size_t g_hyos_module_count;
static bool g_hyos_hooks_installed;
static bool g_hyos_delivered;
static pid_t g_spawner_pid;

static bool is_hyos_spawner(void) {
  char path[sizeof(HYOS_SPAWNER_PATH)];
  return readlink("/proc/self/exe", path, sizeof(path)) == sizeof(path) - 1 &&
         memcmp(path, HYOS_SPAWNER_PATH, sizeof(path) - 1) == 0;
}

static uintptr_t spawner_image_base(void) {
  FILE *maps = fopen("/proc/self/maps", "re");
  if (!maps) return 0;

  uintptr_t base = 0;
  char line[512];
  char path[PATH_MAX];

  while (fgets(line, sizeof(line), maps) != NULL) {
    unsigned long start = 0, end = 0;
    char perms[8] = { 0 };

    if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %4095s", &start, &end, perms, path) != 4) continue;
    if (strcmp(path, HYOS_SPAWNER_PATH) != 0) continue;

    base = (uintptr_t)start;
    break;
  }

  fclose(maps);

  return base;
}

/* The spawner keeps its own name in comm; the app name lives in argv[0], and
   modules scope themselves by that name. */
static const char *hyos_process_name(void) {
  static char name[256];

  name[0] = '\0';
  FILE *file = fopen("/proc/self/cmdline", "re");
  if (file != NULL) {
    size_t len = fread(name, 1, sizeof(name) - 1, file);
    name[len] = '\0';
    fclose(file);
  }

  if (name[0] == '\0' && prctl(PR_GET_NAME, name, 0, 0, 0) != 0) name[0] = '\0';

  return name;
}

static void deliver_app_specialized(const char *package_name, const char *se_info) {
  /* The pool forks children that specialize themselves, and some apps run in the
     process the runtime was injected into, so the caller is always the app. */
  if (g_spawner_pid == 0 || g_hyos_delivered || g_hyos_module_count == 0) return;
  if (package_name == NULL || package_name[0] == '\0') return;

  g_hyos_delivered = true;

  char process_name[256];
  char package_copy[256];
  char se_info_copy[128];

  snprintf(process_name, sizeof(process_name), "%s", hyos_process_name());
  snprintf(package_copy, sizeof(package_copy), "%s", package_name ? package_name : "");
  snprintf(se_info_copy, sizeof(se_info_copy), "%s", se_info ? se_info : "");
  if (process_name[0] == '\0') snprintf(process_name, sizeof(process_name), "hyos_app");
  LOGI("ZN: app specialize %s (%s) in %d", package_name, process_name,
       (int)syscall(__NR_getpid));

  const struct ZnHyosAppSpecializeArgs args = {
    .process_name = process_name,
    .package_name = package_copy,
    .se_info = se_info_copy,
  };

  for (size_t i = 0; i < g_hyos_module_count; ++i) {
    if (g_hyos_modules[i].onAppSpecialized) g_hyos_modules[i].onAppSpecialized(&args);
  }
}

typedef int (*hyos_setcontext_fn)(uid_t uid, int is_system_server, const char *se_info,
                                  const char *pkg_name);

static hyos_setcontext_fn g_original_setcontext;

static int hyos_setcontext_hook(uid_t uid, int is_system_server, const char *se_info,
                                const char *pkg_name) {
  int result = g_original_setcontext ? g_original_setcontext(uid, is_system_server, se_info, pkg_name)
                                     : -1;
  if (result == 0) deliver_app_specialized(pkg_name, se_info);

  return result;
}

static void install_hyos_hooks(void) {
  if (g_hyos_hooks_installed) return;

  uintptr_t base = spawner_image_base();
  if (!base) {
    LOGE("ZN: cannot locate the spawner image for hooking");

    return;
  }

  if (zn_plt_hook((void *)base, "selinux_android_setcontext", (void *)hyos_setcontext_hook,
                  (void **)&g_original_setcontext) != ZN_SUCCESS) {
    LOGE("ZN: cannot hook selinux_android_setcontext; callbacks stay disabled");

    return;
  }

  LOGI("ZN: HyperOS runtime hook installed");
  g_hyos_hooks_installed = true;
}

static int zn_register_hyos_module(const void *value) {
  const struct ZygiskNextHyosModule *module = value;

  if (!module || !module->onAppSpecialized) return ZN_FAILED;
  if (module->target_api_version < 1 ||
      module->target_api_version > ZYGISK_NEXT_HYOS_API_VERSION) {
    LOGE("ZN: unsupported HyperOS api version %d", module->target_api_version);

    return ZN_FAILED;
  }
  if (g_hyos_module_count >= ZN_HYOS_MAX_MODULES) return ZN_FAILED;

  install_hyos_hooks();
  if (!g_hyos_hooks_installed) return ZN_FAILED;
  g_hyos_modules[g_hyos_module_count++] = *module;

  return ZN_SUCCESS;
}

static const struct ZygiskNextRuntime *zn_get_runtime(void) {
  static const struct ZygiskNextRuntime runtime = {
    .type = ZN_RUNTIME_HYOS,
    .api_version = ZYGISK_NEXT_HYOS_API_VERSION,
    .registerModule = zn_register_hyos_module,
  };

  return is_hyos_spawner() ? &runtime : NULL;
}

static int zn_connect_companion(void *handle);

static const struct ZygiskNextAPI g_api = {
    .pltHook = zn_plt_hook,
    .inlineHook = zn_inline_hook,
    .inlineUnhook = zn_inline_unhook,
    .newSymbolResolver = zn_new_symbol_resolver,
    .freeSymbolResolver = zn_free_symbol_resolver,
    .getBaseAddress = zn_get_base_address,
    .symbolLookup = zn_symbol_lookup,
    .forEachSymbols = zn_for_each_symbol,
    .connectCompanion = zn_connect_companion,
    .getRuntime = zn_get_runtime,
};

static int zn_connect_companion(void *handle) {
  struct zn_handle *entry = handle;
  if (!entry || entry->magic != ZN_HANDLE_MAGIC || !entry->companion) return -1;

  /* The socket lives under /data/adb, which a specialised app process cannot
     reach, so the connection is made while the process still can. */
  if (entry->companion_fd >= 0) {
    int fd = dup(entry->companion_fd);
    return fd >= 0 ? fd : entry->companion_fd;
  }

  return rezygiskd_request_zn_companion(entry->path);
}

static bool process_identity(char *name, size_t name_size, char *exe, size_t exe_size) {
  ssize_t len = readlink("/proc/self/exe", exe, exe_size - 1);
  if (len <= 0) return false;
  exe[len] = '\0';

  const char *base = strrchr(exe, '/');
  base = base ? base + 1 : exe;
  if (strlen(base) >= name_size) return false;

  strcpy(name, base);
  return true;
}

/* ZN modules belong to init-spawned services, never to the zygote image. */
bool zn_process_is_target(void) {
  char name[256];
  char exe[PATH_MAX];
  if (!process_identity(name, sizeof(name), exe, sizeof(exe))) return false;

  return strncmp(name, "app_process", 11) != 0;
}

void zn_load_modules(void) {
  if (g_spawner_pid == 0) g_spawner_pid = (pid_t)syscall(__NR_getpid);

  if (!zn_process_is_target()) return;

  char name[256];
  char exe[PATH_MAX];
  if (!process_identity(name, sizeof(name), exe, sizeof(exe))) return;

  struct zn_module_ref refs[ZN_MAX_MODULES];
  size_t count = rezygiskd_read_zn_modules(name, exe, refs, ZN_MAX_MODULES - g_module_count);
  if (count == 0) return;

  for (size_t i = 0; i < count; ++i) {
    struct zn_module_ref *ref = &refs[i];
    if (g_module_count == ZN_MAX_MODULES) {
      close(ref->fd);
      continue;
    }
    struct zn_loaded *slot = &g_modules[g_module_count];

    char loader_path[64];
    snprintf(loader_path, sizeof(loader_path), "/proc/self/fd/%d", ref->fd);

    if (!csoloader_load(&slot->lib, loader_path)) {
      LOGE("ZN: failed to load %s", ref->path);
      close(ref->fd);
      continue;
    }

    void *entry = csoloader_get_symbol(&slot->lib, "zn_module");
    if (!entry) {
      LOGE("ZN: %s does not export zn_module", ref->path);
      csoloader_unload(&slot->lib);
      close(ref->fd);
      continue;
    }

    const struct ZygiskNextModule *module = entry;
    if (module->target_api_version < 1 || module->target_api_version > ZYGISK_NEXT_API_VERSION ||
        !module->onModuleLoaded) {
      LOGE("ZN: %s declares unsupported api version %d", ref->path, module->target_api_version);
      csoloader_unload(&slot->lib);
      close(ref->fd);
      continue;
    }

    slot->handle.magic = ZN_HANDLE_MAGIC;
    strcpy(slot->handle.path, ref->path);
    slot->handle.companion = ref->companion;
    slot->handle.companion_fd = -1;
    if (ref->companion) {
      slot->handle.companion_fd = rezygiskd_request_zn_companion(ref->path);
      if (slot->handle.companion_fd < 0) LOGE("ZN: %s companion unavailable", ref->path);
    }
    ++g_module_count;

    close(ref->fd);

    LOGI("ZN: loading %s (api %d, companion %d)", ref->path, module->target_api_version,
         ref->companion ? 1 : 0);
    module->onModuleLoaded(&slot->handle, &g_api);
  }
}
