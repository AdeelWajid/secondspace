/**
 * NEXTVM Native Hook Library
 *
 * PLT/GOT hooks for libc file I/O functions to implement native-level
 * path redirection for guest app file isolation.
 *
 * Hooks (installed via GOT patching):
 *   - open/openat     → redirect file paths to sandbox
 *   - access          → check sandbox path instead
 *   - stat/lstat      → stat sandbox path instead
 *   - readlink        → return sandbox path
 *   - fopen           → redirect file paths to sandbox
 *   - __system_property_get → spoof device properties
 *
 * Architecture:
 *   Java (NativeHookBridge.kt)
 *     ↓ JNI
 *   native-hook.cpp (this file)
 *     ↓ GOT/PLT patching via dl_iterate_phdr + ELF parsing
 *   hooked libc functions ←→ original libc functions (via saved pointers)
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>
#include <link.h>
#include <elf.h>

// Define ELF_R_SYM if not provided by NDK headers
#ifndef ELF_R_SYM
#ifdef __LP64__
#define ELF_R_SYM(info) ELF64_R_SYM(info)
#else
#define ELF_R_SYM(info) ELF32_R_SYM(info)
#endif
#endif

#include <ctime>
#include <pthread.h>
#include <sys/syscall.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdarg>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <signal.h>
#include <setjmp.h>

#define LOG_TAG "NextVM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== Global State ====================

static bool g_initialized = false;
static bool g_hooks_installed = false;
static std::mutex g_mutex;

// Path redirection: source prefix → target prefix
static std::unordered_map<std::string, std::string> g_path_redirects;

// Property spoofing: property name → spoofed value
static std::unordered_map<std::string, std::string> g_property_spoofs;

// Hidden paths: paths that should appear non-existent
static std::unordered_set<std::string> g_hidden_paths;

// /proc/self spoofing
static int g_spoofed_pid = -1;
static std::string g_spoofed_package_name;

// Host package data dir prefix
static std::string g_host_data_prefix;

// Virtual data root
static std::string g_virtual_data_root;

// ==================== Original Function Pointers ====================

typedef int (*orig_open_t)(const char*, int, ...);
typedef int (*orig_openat_t)(int, const char*, int, ...);
typedef int (*orig_access_t)(const char*, int);
typedef int (*orig_stat_t)(const char*, struct stat*);
typedef int (*orig_lstat_t)(const char*, struct stat*);
typedef ssize_t (*orig_readlink_t)(const char*, char*, size_t);
typedef FILE* (*orig_fopen_t)(const char*, const char*);
typedef int (*orig_system_property_get_t)(const char*, char*);

static orig_open_t real_open = nullptr;
static orig_openat_t real_openat = nullptr;
static orig_access_t real_access = nullptr;
static orig_stat_t real_stat = nullptr;
static orig_lstat_t real_lstat = nullptr;
static orig_readlink_t real_readlink = nullptr;
static orig_fopen_t real_fopen = nullptr;
static orig_system_property_get_t real_system_property_get = nullptr;

// ==================== Path Redirection Logic ====================

/**
 * Check if a path is hidden.
 */
static bool is_path_hidden(const char* path) {
    if (path == nullptr || g_hidden_paths.empty()) return false;
    return g_hidden_paths.count(std::string(path)) > 0;
}

/**
 * Check if a path needs redirection and return the redirected path.
 * Returns empty string if no redirection needed.
 */
static std::string redirect_path(const char* path) {
    if (path == nullptr || path[0] == '\0') return "";
    if (g_path_redirects.empty()) return "";

    std::string path_str(path);

    // Check each registered redirect prefix (longest match wins)
    std::string best_from;
    std::string best_to;
    for (const auto& redirect : g_path_redirects) {
        if (path_str.compare(0, redirect.first.length(), redirect.first) == 0) {
            if (redirect.first.length() > best_from.length()) {
                best_from = redirect.first;
                best_to = redirect.second;
            }
        }
    }

    if (!best_from.empty()) {
        return best_to + path_str.substr(best_from.length());
    }

    // Special case: /data/user/0/ is the same as /data/data/
    if (path_str.compare(0, 13, "/data/user/0/") == 0) {
        std::string alt_path = "/data/data/" + path_str.substr(13);
        return redirect_path(alt_path.c_str());
    }

    return "";
}

/**
 * Check if a path is a /proc/self path that needs spoofing.
 */
static bool is_proc_self_path(const char* path) {
    if (path == nullptr) return false;
    return strncmp(path, "/proc/self/", 11) == 0;
}

// ==================== Hook Implementations ====================

/**
 * Hooked open() — redirects file paths to sandbox, hides paths.
 */
static int hooked_open(const char* path, int flags, ...) {
    // Check hidden paths
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("open: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_open(actual_path, flags, mode);
    }
    return real_open(actual_path, flags);
}

/**
 * Hooked openat() — redirects file paths to sandbox, hides paths.
 */
static int hooked_openat(int dirfd, const char* path, int flags, ...) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("openat: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_openat(dirfd, actual_path, flags, mode);
    }
    return real_openat(dirfd, actual_path, flags);
}

/**
 * Hooked access() — checks sandbox path instead, hides paths.
 */
static int hooked_access(const char* path, int mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_access(actual_path, mode);
}

/**
 * Hooked stat() — stats sandbox path instead, hides paths.
 */
static int hooked_stat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_stat(actual_path, buf);
}

/**
 * Hooked lstat() — lstats sandbox path instead, hides paths.
 */
static int hooked_lstat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_lstat(actual_path, buf);
}

/**
 * Hooked readlink() — returns sandbox path instead.
 */
static ssize_t hooked_readlink(const char* path, char* buf, size_t bufsiz) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    // Spoof /proc/self/exe
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/exe") == 0 &&
        !g_spoofed_package_name.empty()) {
        // Return a fake exe path
        std::string fake = "/system/bin/app_process64";
        size_t len = fake.length();
        if (len > bufsiz) len = bufsiz;
        memcpy(buf, fake.c_str(), len);
        return static_cast<ssize_t>(len);
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_readlink(actual_path, buf, bufsiz);
}

/**
 * Hooked fopen() — redirects file paths to sandbox, hides paths.
 */
static FILE* hooked_fopen(const char* path, const char* mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return nullptr;
    }

    // Spoof /proc/self/cmdline
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/cmdline") == 0 &&
        !g_spoofed_package_name.empty()) {
        // Create a temp file with spoofed cmdline
        FILE* tmp = tmpfile();
        if (tmp) {
            fwrite(g_spoofed_package_name.c_str(), 1, g_spoofed_package_name.length() + 1, tmp);
            fseek(tmp, 0, SEEK_SET);
            return tmp;
        }
    }

    // Spoof /proc/self/maps — filter out NEXTVM entries
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/maps") == 0) {
        FILE* real_maps = real_fopen(path, mode);
        if (real_maps) {
            FILE* tmp = tmpfile();
            if (tmp) {
                char line[1024];
                while (fgets(line, sizeof(line), real_maps)) {
                    // Hide entries containing our library names
                    if (strstr(line, "nextvm") == nullptr &&
                        strstr(line, "lsplant") == nullptr &&
                        strstr(line, "dobby") == nullptr &&
                        strstr(line, "bhook") == nullptr &&
                        strstr(line, "xhook") == nullptr &&
                        strstr(line, "substrate") == nullptr &&
                        strstr(line, "xposed") == nullptr) {
                        fputs(line, tmp);
                    }
                }
                fclose(real_maps);
                fseek(tmp, 0, SEEK_SET);
                return tmp;
            }
            fclose(real_maps);
        }
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("fopen: %s -> %s", path, actual_path);
    }

    return real_fopen(actual_path, mode);
}

/**
 * Hooked __system_property_get() — returns spoofed device properties.
 */
static int hooked_system_property_get(const char* name, char* value) {
    // Check if we have a spoofed value for this property
    if (name != nullptr && !g_property_spoofs.empty()) {
        std::string prop_name(name);
        auto it = g_property_spoofs.find(prop_name);
        if (it != g_property_spoofs.end()) {
            const std::string& spoofed = it->second;
            size_t len = spoofed.length();
            if (len > 91) len = 91; // PROP_VALUE_MAX - 1
            memcpy(value, spoofed.c_str(), len);
            value[len] = '\0';
            LOGD("property_get: %s -> %s (spoofed)", name, value);
            return static_cast<int>(len);
        }
    }

    // No spoof — call real implementation
    return real_system_property_get(name, value);
}

// ==================== GOT/PLT Hook Installation ====================

// SIGSEGV-safe memory probe: returns false if the address is not readable
static thread_local sigjmp_buf g_probe_jmp;
static thread_local volatile bool g_probing = false;

static void probe_signal_handler(int sig) {
    if (g_probing) {
        siglongjmp(g_probe_jmp, 1);
    }
}

/**
 * Check if a memory address is safely readable using mincore() as a fast check,
 * falling back to a SIGSEGV-safe read probe.
 */
static bool is_address_readable(const void* addr, size_t len) {
    if (addr == nullptr || len == 0) return false;
    uintptr_t uaddr = reinterpret_cast<uintptr_t>(addr);
    if (uaddr < 0x1000) return false;

    size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    unsigned char present = 0;
    uintptr_t page_start = uaddr & ~(page_size - 1);
    if (mincore(reinterpret_cast<void*>(page_start), page_size, &present) != 0) return false;
    uintptr_t end_page = (uaddr + len - 1) & ~(page_size - 1);
    if (end_page != page_start &&
        mincore(reinterpret_cast<void*>(end_page), page_size, &present) != 0) {
        return false;
    }
    return true;
}

// Bounds of the library currently being patched. A file offset must land
// inside this range. Treating "any readable address" as already relocated
// made small offsets match the Java heap, then mprotect marked that page
// read-only and the next lock or GC faulted with SEGV_ACCERR.
static uintptr_t g_lib_lo = 0;
static uintptr_t g_lib_hi = 0;

static void set_lib_bounds(const struct dl_phdr_info* info) {
    g_lib_lo = 0;
    g_lib_hi = 0;
    if (info == nullptr) return;
    uintptr_t lo = ~static_cast<uintptr_t>(0);
    uintptr_t hi = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type != PT_LOAD || info->dlpi_phdr[i].p_memsz == 0) continue;
        uintptr_t start = static_cast<uintptr_t>(info->dlpi_addr) + info->dlpi_phdr[i].p_vaddr;
        uintptr_t end = start + info->dlpi_phdr[i].p_memsz;
        if (start < lo) lo = start;
        if (end > hi) hi = end;
    }
    if (hi > lo) {
        g_lib_lo = lo;
        g_lib_hi = hi;
    }
}

static bool in_current_lib(uintptr_t value) {
    return g_lib_hi > g_lib_lo && value >= g_lib_lo && value < g_lib_hi;
}

static uintptr_t elf_addr(uintptr_t base, uintptr_t value) {
    if (value == 0) return 0;
    if (in_current_lib(value)) return value;
    if (base != 0) {
        uintptr_t biased = base + value;
        if (in_current_lib(biased)) return biased;
    }
    return 0;
}

// ==================== Game speed (monotonic clock + sleep) ====================

typedef int (*orig_clock_gettime_t)(clockid_t, struct timespec*);
typedef int (*orig_nanosleep_t)(const struct timespec*, struct timespec*);
typedef int (*orig_usleep_t)(useconds_t);

static orig_clock_gettime_t real_clock_gettime = nullptr;
static orig_clock_gettime_t g_unity_clock_orig = nullptr;
static orig_nanosleep_t real_nanosleep = nullptr;
static orig_usleep_t real_usleep = nullptr;

static std::atomic<float> g_speed{1.f};
static std::atomic<bool> g_scale_clock{false};
static std::atomic<bool> g_scale_sleep{false};
static std::atomic<int64_t> g_real_base{0};
static std::atomic<int64_t> g_virt_base{0};
static std::atomic<bool> g_clock_inited{false};
static bool g_raw_clock_ok = false;
static bool g_raw_sleep_ok = false;

static int64_t ts_to_ns(const struct timespec* ts) {
    return static_cast<int64_t>(ts->tv_sec) * 1000000000LL + ts->tv_nsec;
}

static void ns_to_ts(int64_t ns, struct timespec* ts) {
    if (ns < 0) ns = 0;
    ts->tv_sec = static_cast<time_t>(ns / 1000000000LL);
    ts->tv_nsec = static_cast<long>(ns % 1000000000LL);
}

static bool is_monotonic_clock(clockid_t clk) {
    return clk == CLOCK_MONOTONIC || clk == CLOCK_MONOTONIC_RAW || clk == CLOCK_BOOTTIME
#ifdef CLOCK_MONOTONIC_COARSE
        || clk == CLOCK_MONOTONIC_COARSE
#endif
        ;
}

static bool is_time_symbol(const char* name) {
    return strcmp(name, "clock_gettime") == 0 ||
           strcmp(name, "nanosleep") == 0 ||
           strcmp(name, "usleep") == 0;
}

// Read the real clock through the kernel so a hooked libc function cannot recurse.
static int raw_clock_gettime(clockid_t clk, struct timespec* ts) {
    if (ts == nullptr) return -1;
    long rc = syscall(__NR_clock_gettime, clk, ts);
    return rc == 0 ? 0 : -1;
}

static int raw_nanosleep(const struct timespec* req, struct timespec* rem) {
    long rc = syscall(__NR_nanosleep, req, rem);
    return rc == 0 ? 0 : -1;
}

static void scale_monotonic(struct timespec* ts) {
    if (!g_scale_clock.load(std::memory_order_relaxed) || ts == nullptr) return;
    float speed = g_speed.load(std::memory_order_relaxed);
    if (speed == 1.f || speed <= 0.f) return;
    int64_t real_now = ts_to_ns(ts);
    int64_t virt = g_virt_base.load(std::memory_order_relaxed) +
        static_cast<int64_t>((static_cast<double>(real_now - g_real_base.load(std::memory_order_relaxed))) * speed);
    ns_to_ts(virt, ts);
}

static int hooked_clock_gettime(clockid_t clk, struct timespec* ts) {
    int rc = -1;
    if (g_raw_clock_ok) {
        rc = syscall(__NR_clock_gettime, clk, ts) == 0 ? 0 : -1;
    } else if (real_clock_gettime != nullptr) {
        rc = real_clock_gettime(clk, ts);
    }
    if (rc != 0 || ts == nullptr) return rc;
    if (is_monotonic_clock(clk)) scale_monotonic(ts);
    return 0;
}

static int hooked_nanosleep(const struct timespec* req, struct timespec* rem) {
    if (req == nullptr) return -1;
    auto invoke = [&](const struct timespec* which) {
        if (g_raw_sleep_ok) return raw_nanosleep(which, rem);
        if (real_nanosleep != nullptr) return real_nanosleep(which, rem);
        return -1;
    };
    if (!g_scale_sleep.load(std::memory_order_relaxed)) return invoke(req);
    float speed = g_speed.load(std::memory_order_relaxed);
    if (speed == 1.f || speed <= 0.f) return invoke(req);
    int64_t original = ts_to_ns(req);
    int64_t scaled = static_cast<int64_t>(static_cast<double>(original) / speed);
    // A zero wait turns the caller into a spin. That includes engine threads
    // and is what takes down the garbage collector at 5x and 10x.
    if (original > 0 && scaled < 1000000LL) scaled = original < 1000000LL ? original : 1000000LL;
    struct timespec shortened;
    ns_to_ts(scaled, &shortened);
    return invoke(&shortened);
}

static int hooked_usleep(useconds_t usec) {
    if (!g_raw_sleep_ok) {
        if (real_usleep != nullptr) return real_usleep(usec);
        return -1;
    }
    float speed = 1.f;
    if (g_scale_sleep.load(std::memory_order_relaxed)) {
        speed = g_speed.load(std::memory_order_relaxed);
        if (speed <= 0.f) speed = 1.f;
    }
    useconds_t scaled = static_cast<useconds_t>(static_cast<double>(usec) / speed);
    if (usec > 0 && scaled < 1000) scaled = usec < 1000 ? usec : 1000;
    struct timespec req;
    ns_to_ts(static_cast<int64_t>(scaled) * 1000LL, &req);
    return raw_nanosleep(&req, nullptr);
}

static void resolve_time_symbols() {
    if (!real_clock_gettime)
        real_clock_gettime = (orig_clock_gettime_t)dlsym(RTLD_DEFAULT, "clock_gettime");
    if (!real_nanosleep)
        real_nanosleep = (orig_nanosleep_t)dlsym(RTLD_DEFAULT, "nanosleep");
    if (!real_usleep)
        real_usleep = (orig_usleep_t)dlsym(RTLD_DEFAULT, "usleep");
}

static void reanchor_clock(float new_speed, bool clock_on) {
    resolve_time_symbols();
    struct timespec ts{};
    if (raw_clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        g_speed.store(new_speed);
        g_scale_clock.store(clock_on);
        return;
    }
    int64_t real_now = ts_to_ns(&ts);
    bool was_on = g_clock_inited.load() && g_scale_clock.load() && g_speed.load() != 1.f;
    if (!was_on) {
        g_real_base.store(real_now);
        g_virt_base.store(real_now);
        g_clock_inited.store(true);
    } else {
        float old = g_speed.load();
        int64_t virt = g_virt_base.load() +
            static_cast<int64_t>((static_cast<double>(real_now - g_real_base.load())) * old);
        g_virt_base.store(virt);
        g_real_base.store(real_now);
    }
    g_speed.store(new_speed);
    g_scale_clock.store(clock_on);
}

/**
 * Hook entry: maps a symbol name to our hook function and the saved original pointer.
 */
struct HookEntry {
    const char* symbol;
    void* hook_func;
    void** original_func;
};

static HookEntry g_hook_entries[] = {
    {"open",                    (void*)hooked_open,                 (void**)&real_open},
    {"openat",                  (void*)hooked_openat,               (void**)&real_openat},
    {"access",                  (void*)hooked_access,               (void**)&real_access},
    {"stat",                    (void*)hooked_stat,                 (void**)&real_stat},
    {"lstat",                   (void*)hooked_lstat,                (void**)&real_lstat},
    {"readlink",                (void*)hooked_readlink,             (void**)&real_readlink},
    {"fopen",                   (void*)hooked_fopen,                (void**)&real_fopen},
    {"__system_property_get",   (void*)hooked_system_property_get,  (void**)&real_system_property_get},
    {"clock_gettime",           (void*)hooked_clock_gettime,        (void**)&real_clock_gettime},
    {"nanosleep",               (void*)hooked_nanosleep,            (void**)&real_nanosleep},
    {"usleep",                  (void*)hooked_usleep,               (void**)&real_usleep},
};
static constexpr int g_hook_count = sizeof(g_hook_entries) / sizeof(g_hook_entries[0]);

// When set, clock_gettime in game libraries calls this instead of the scaler.
// The probe only runs on threads that already entered the game library.
static void* g_clock_probe_hook = nullptr;
static void** g_clock_probe_orig = nullptr;
static bool g_clock_probe_only = false;
static const char* g_one_symbol = nullptr;
static void* g_one_hook = nullptr;
static void** g_one_orig = nullptr;
static bool g_one_symbol_only = false;

/**
 * Replace a single GOT entry.
 * Makes the GOT page writable, swaps the pointer, restores permissions.
 *
 * @param got_addr  Pointer to the GOT entry to patch
 * @param new_func  The hook function to install
 * @param old_func  Output: the original function pointer that was in the GOT
 * @return true on success
 */
static bool patch_got_entry(void** got_addr, void* new_func, void** old_func) {
    if (got_addr == nullptr || !in_current_lib(reinterpret_cast<uintptr_t>(got_addr))) return false;
    // Validate the GOT address is in readable memory before dereferencing
    if (!is_address_readable(got_addr, sizeof(void*))) {
        LOGW("GOT addr %p not readable, skipping", got_addr);
        return false;
    }
    if (*got_addr == nullptr) return false;

    // Don't patch if already pointing to our hook
    if (*got_addr == new_func) return true;

    // Save the original
    if (old_func && *old_func == nullptr) {
        *old_func = *got_addr;
    }

    // Calculate page-aligned address
    size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    uintptr_t page_start = reinterpret_cast<uintptr_t>(got_addr) & ~(page_size - 1);

    // Make page writable
    if (mprotect(reinterpret_cast<void*>(page_start), page_size,
                 PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect RW failed for GOT entry at %p: %s",
             got_addr, strerror(errno));
        return false;
    }

    // Patch the GOT entry
    *got_addr = new_func;

    // Restore page permissions (read-only)
    mprotect(reinterpret_cast<void*>(page_start), page_size, PROT_READ);

    return true;
}

static bool patch_one_symbol(uintptr_t base_addr, uintptr_t r_offset, const char* sym_name,
                             const char* lib_name) {
    if (g_one_symbol == nullptr || strcmp(sym_name, g_one_symbol) != 0) return false;
    void** got_addr = reinterpret_cast<void**>(elf_addr(base_addr, r_offset));
    if (patch_got_entry(got_addr, g_one_hook, g_one_orig)) {
        LOGI("Hooked %s in %s", sym_name, lib_name);
    }
    return true;
}

/**
 * Process a single loaded shared object: find its GOT and patch entries.
 *
 * Parses the ELF dynamic section to locate .rel.plt / .rela.plt relocations,
 * then patches GOT entries for our target symbols.
 */
static void patch_library_got(uintptr_t base_addr, const char* lib_name,
                               const ElfW(Dyn)* dynamic, bool time_only) {
    if (dynamic == nullptr) return;

    // Find required dynamic entries
    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
    const ElfW(Rel)* rel_plt = nullptr;
    const ElfW(Rela)* rela_plt = nullptr;
    size_t rel_plt_size = 0;
    size_t rela_plt_size = 0;
    bool use_rela = false;

    for (const ElfW(Dyn)* dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym)*>(dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(dyn->d_un.d_ptr);
                break;
            case DT_JMPREL:
                // Could be REL or RELA depending on DT_PLTREL
                rel_plt = reinterpret_cast<const ElfW(Rel)*>(dyn->d_un.d_ptr);
                rela_plt = reinterpret_cast<const ElfW(Rela)*>(dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                rel_plt_size = dyn->d_un.d_val;
                rela_plt_size = dyn->d_un.d_val;
                break;
            case DT_PLTREL:
                use_rela = (dyn->d_un.d_val == DT_RELA);
                break;
        }
    }

    if (symtab == nullptr || strtab == nullptr) return;
    if (rel_plt == nullptr && rela_plt == nullptr) return;

    symtab = reinterpret_cast<const ElfW(Sym)*>(elf_addr(base_addr, reinterpret_cast<uintptr_t>(symtab)));
    strtab = reinterpret_cast<const char*>(elf_addr(base_addr, reinterpret_cast<uintptr_t>(strtab)));
    rel_plt = reinterpret_cast<const ElfW(Rel)*>(elf_addr(base_addr, reinterpret_cast<uintptr_t>(rel_plt)));
    rela_plt = reinterpret_cast<const ElfW(Rela)*>(elf_addr(base_addr, reinterpret_cast<uintptr_t>(rela_plt)));

    // Verify symtab and strtab are actually readable memory
    if (!is_address_readable(symtab, sizeof(ElfW(Sym))) ||
        !is_address_readable(strtab, 1)) {
        LOGW("Skipping %s: symtab(%p) or strtab(%p) not in readable memory",
             lib_name, symtab, strtab);
        return;
    }

    // Also validate relocation table pointer
    if (use_rela && rela_plt) {
        if (!is_address_readable(rela_plt, sizeof(ElfW(Rela)))) {
            LOGW("Skipping %s: rela_plt(%p) not readable", lib_name, rela_plt);
            return;
        }
    } else if (rel_plt) {
        if (!is_address_readable(rel_plt, sizeof(ElfW(Rel)))) {
            LOGW("Skipping %s: rel_plt(%p) not readable", lib_name, rel_plt);
            return;
        }
    }

    if (use_rela && rela_plt != nullptr && rela_plt_size > 0) {
        // Process RELA entries (arm64, x86_64)
        size_t count = rela_plt_size / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            const ElfW(Rela)& entry = rela_plt[i];
            size_t sym_idx = ELF_R_SYM(entry.r_info);
            if (sym_idx == 0) continue;

            // Validate sym_idx access is safe
            const ElfW(Sym)* sym_entry_ptr = &symtab[sym_idx];
            if (!is_address_readable(sym_entry_ptr, sizeof(ElfW(Sym)))) continue;

            uint32_t st_name = symtab[sym_idx].st_name;
            const char* sym_name = strtab + st_name;
            if (!is_address_readable(sym_name, 1)) continue;
            if (sym_name[0] == '\0') continue;
            if (patch_one_symbol(base_addr, entry.r_offset, sym_name, lib_name)) continue;
            if (g_one_symbol_only) continue;
            if (time_only != is_time_symbol(sym_name)) continue;
            if (g_clock_probe_only && strcmp(sym_name, "clock_gettime") != 0) continue;

            // Check if this symbol is one we want to hook
            for (int h = 0; h < g_hook_count; h++) {
                if (strcmp(sym_name, g_hook_entries[h].symbol) == 0) {
                    void** got_addr = reinterpret_cast<void**>(elf_addr(base_addr, entry.r_offset));
                    void* hook_fn = g_hook_entries[h].hook_func;
                    void** orig_slot = g_hook_entries[h].original_func;
                    if (g_clock_probe_hook != nullptr && strcmp(sym_name, "clock_gettime") == 0) {
                        hook_fn = g_clock_probe_hook;
                        orig_slot = g_clock_probe_orig;
                    }
                    if (patch_got_entry(got_addr, hook_fn, orig_slot)) {
                        LOGD("Hooked %s in %s (RELA)", sym_name, lib_name);
                    }
                    break;
                }
            }
        }
    } else if (rel_plt != nullptr && rel_plt_size > 0) {
        // Process REL entries (arm32, x86)
        size_t count = rel_plt_size / sizeof(ElfW(Rel));
        for (size_t i = 0; i < count; i++) {
            const ElfW(Rel)& entry = rel_plt[i];
            size_t sym_idx = ELF_R_SYM(entry.r_info);
            if (sym_idx == 0) continue;

            // Validate sym_idx access is safe
            const ElfW(Sym)* sym_entry_ptr = &symtab[sym_idx];
            if (!is_address_readable(sym_entry_ptr, sizeof(ElfW(Sym)))) continue;

            uint32_t st_name = symtab[sym_idx].st_name;
            const char* sym_name = strtab + st_name;
            if (!is_address_readable(sym_name, 1)) continue;
            if (sym_name[0] == '\0') continue;
            if (patch_one_symbol(base_addr, entry.r_offset, sym_name, lib_name)) continue;
            if (g_one_symbol_only) continue;
            if (time_only != is_time_symbol(sym_name)) continue;
            if (g_clock_probe_only && strcmp(sym_name, "clock_gettime") != 0) continue;

            for (int h = 0; h < g_hook_count; h++) {
                if (strcmp(sym_name, g_hook_entries[h].symbol) == 0) {
                    void** got_addr = reinterpret_cast<void**>(elf_addr(base_addr, entry.r_offset));
                    void* hook_fn = g_hook_entries[h].hook_func;
                    void** orig_slot = g_hook_entries[h].original_func;
                    if (g_clock_probe_hook != nullptr && strcmp(sym_name, "clock_gettime") == 0) {
                        hook_fn = g_clock_probe_hook;
                        orig_slot = g_clock_probe_orig;
                    }
                    if (patch_got_entry(got_addr, hook_fn, orig_slot)) {
                        LOGD("Hooked %s in %s (REL)", sym_name, lib_name);
                    }
                    break;
                }
            }
        }
    }
}

/**
 * dl_iterate_phdr callback — called for each loaded shared object.
 * We find the PT_DYNAMIC segment and use it to locate GOT entries.
 */
static int install_hooks_callback(struct dl_phdr_info* info, size_t /*size*/, void* /*data*/) {
    if (info->dlpi_name == nullptr) return 0;

    const char* name = info->dlpi_name;

    // Skip empty names (main executable) and our own library
    if (name[0] == '\0') return 0;
    if (strstr(name, "libnextvm") != nullptr) return 0;

    // Skip the linker itself
    if (strstr(name, "linker") != nullptr) return 0;

    // Skip vDSO and other kernel-mapped objects (no valid GOT to patch)
    if (strstr(name, "vdso") != nullptr) return 0;
    if (strstr(name, "[vdso]") != nullptr) return 0;

    // Skip libraries that commonly have RELRO-protected GOTs causing issues
    if (strstr(name, "libart.so") != nullptr) return 0;
    if (strstr(name, "libhwbinder") != nullptr) return 0;

    // Find PT_DYNAMIC segment
    const ElfW(Dyn)* dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(
                info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }

    if (dynamic != nullptr) {
        set_lib_bounds(info);
        patch_library_got(info->dlpi_addr, name, dynamic, false);
    }

    return 0;
}

static bool is_game_library(const char* name) {
    if (name == nullptr) return false;
    return strstr(name, "libunity.so") != nullptr ||
           strstr(name, "libil2cpp.so") != nullptr ||
           strstr(name, "libmain.so") != nullptr ||
           strstr(name, "libgame.so") != nullptr ||
           strstr(name, "libmono") != nullptr ||
           strstr(name, "libUnreal") != nullptr ||
           strstr(name, "libUE4.so") != nullptr ||
           strstr(name, "virtual/data") != nullptr ||
           strstr(name, "virtual/apks") != nullptr;
}

static int install_time_hooks_callback(struct dl_phdr_info* info, size_t /*size*/, void* /*data*/) {
    if (info->dlpi_name == nullptr) return 0;
    const char* name = info->dlpi_name;
    if (!is_game_library(name)) return 0;

    const ElfW(Dyn)* dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(
                info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dynamic != nullptr) {
        set_lib_bounds(info);
        patch_library_got(info->dlpi_addr, name, dynamic, true);
    }
    return 0;
}

static void install_time_hooks() {
    resolve_time_symbols();
    dl_iterate_phdr(install_time_hooks_callback, nullptr);
}

/**
 * Install GOT/PLT hooks into all currently loaded shared objects.
 */
static bool install_plt_hooks() {
    LOGI("Installing PLT hooks via GOT patching...");

    // Resolve original function pointers first (before hooking)
    if (!real_open)
        real_open = (orig_open_t)dlsym(RTLD_DEFAULT, "open");
    if (!real_openat)
        real_openat = (orig_openat_t)dlsym(RTLD_DEFAULT, "openat");
    if (!real_access)
        real_access = (orig_access_t)dlsym(RTLD_DEFAULT, "access");
    if (!real_stat)
        real_stat = (orig_stat_t)dlsym(RTLD_DEFAULT, "stat");
    if (!real_lstat)
        real_lstat = (orig_lstat_t)dlsym(RTLD_DEFAULT, "lstat");
    if (!real_readlink)
        real_readlink = (orig_readlink_t)dlsym(RTLD_DEFAULT, "readlink");
    if (!real_fopen)
        real_fopen = (orig_fopen_t)dlsym(RTLD_DEFAULT, "fopen");
    if (!real_system_property_get)
        real_system_property_get = (orig_system_property_get_t)dlsym(
            RTLD_DEFAULT, "__system_property_get");
    resolve_time_symbols();

    if (!real_open || !real_openat || !real_access || !real_stat) {
        LOGE("Failed to resolve one or more libc functions via dlsym");
        return false;
    }

    // Iterate all loaded libraries and patch GOT entries
    dl_iterate_phdr(install_hooks_callback, nullptr);

    LOGI("PLT hooks installed successfully");
    return true;
}

// ==================== JNI Bridge ====================

extern "C" {

/**
 * Initialize the native hook engine.
 * Resolves original function pointers and installs GOT/PLT hooks.
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeInit(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_initialized) {
        LOGI("Native hook engine already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing NEXTVM native hook engine...");

    // Install PLT hooks via GOT patching
    g_hooks_installed = install_plt_hooks();
    if (!g_hooks_installed) {
        LOGW("GOT/PLT hooking failed — falling back to Java-level hooks only");
    }

    g_initialized = true;
    LOGI("Native hook engine initialized (hooks_installed=%d)", g_hooks_installed);
    return JNI_TRUE;
}

/**
 * Add a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeAddPathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath, jstring targetPath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);
    const char* tgt = env->GetStringUTFChars(targetPath, nullptr);

    if (src && tgt) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_path_redirects[std::string(src)] = std::string(tgt);
        LOGI("Path redirect added: %s -> %s", src, tgt);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
    if (tgt) env->ReleaseStringUTFChars(targetPath, tgt);
}

/**
 * Remove a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeRemovePathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);

    if (src) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_path_redirects.erase(std::string(src));
        LOGI("Path redirect removed: %s", src);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
}

/**
 * Clear all path redirection rules.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeClearPathRedirections(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path_redirects.clear();
    LOGI("All path redirects cleared");
}

/**
 * Set up /proc/self spoofing at native level.
 * Spoofs cmdline, comm, exe, and maps filtering.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSpoofProcSelf(
    JNIEnv* env, jobject thiz, jint pid, jstring packageName)
{
    (void)thiz;
    const char* pkg = env->GetStringUTFChars(packageName, nullptr);
    if (pkg) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_spoofed_pid = pid;
        g_spoofed_package_name = std::string(pkg);
        LOGI("/proc/self spoof set: pid=%d, pkg=%s", pid, pkg);
        env->ReleaseStringUTFChars(packageName, pkg);
    }
}

/**
 * Override a system property at native level.
 * MUST match Kotlin: nativeSpoofSystemProperty(key: String, value: String)
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSpoofSystemProperty(
    JNIEnv* env, jobject thiz, jstring key, jstring value)
{
    (void)thiz;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);

    if (k && v) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_property_spoofs[std::string(k)] = std::string(v);
        LOGI("Property spoof set: %s -> %s", k, v);
    }

    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

/**
 * Hide a path at native level (return ENOENT on access).
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeHidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_hidden_paths.insert(std::string(p));
        LOGD("Path hidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Unhide a path at native level.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeUnhidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_hidden_paths.erase(std::string(p));
        LOGD("Path unhidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Clean up all native hooks and state.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeCleanup(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path_redirects.clear();
    g_property_spoofs.clear();
    g_hidden_paths.clear();
    g_spoofed_pid = -1;
    g_spoofed_package_name.clear();
    g_host_data_prefix.clear();
    g_virtual_data_root.clear();
    // Note: GOT patches remain in place but do nothing without redirect rules
    LOGI("Native hook state cleaned up");
}

/**
 * Set the host data directory prefix.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSetHostDataPrefix(
    JNIEnv* env, jobject thiz, jstring prefix)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(prefix, nullptr);
    if (p) {
        g_host_data_prefix = std::string(p);
        env->ReleaseStringUTFChars(prefix, p);
        LOGI("Host data prefix: %s", g_host_data_prefix.c_str());
    }
}

/**
 * Set the virtual data root directory.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSetVirtualDataRoot(
    JNIEnv* env, jobject thiz, jstring root)
{
    (void)thiz;
    const char* r = env->GetStringUTFChars(root, nullptr);
    if (r) {
        g_virtual_data_root = std::string(r);
        env->ReleaseStringUTFChars(root, r);
        LOGI("Virtual data root: %s", g_virtual_data_root.c_str());
    }
}

/**
 * Get the current redirect count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeGetRedirectCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return static_cast<jint>(g_path_redirects.size());
}

/**
 * Get the current property spoof count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeGetPropertySpoofCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return static_cast<jint>(g_property_spoofs.size());
}

/**
 * Check if the native engine is initialized.
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeIsInitialized(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

static bool g_inline_clock = false;
static bool g_inline_sleep = false;
static bool g_java_time_hooked = false;

static int read_real_clock(clockid_t clk, struct timespec* ts) {
    if (g_raw_clock_ok) {
        return syscall(__NR_clock_gettime, clk, ts) == 0 ? 0 : -1;
    }
    if (real_clock_gettime != nullptr) return real_clock_gettime(clk, ts);
    return -1;
}

static jlong virtual_ns(clockid_t clk) {
    struct timespec ts{};
    if (read_real_clock(clk, &ts) != 0) return 0;
    if (is_monotonic_clock(clk)) scale_monotonic(&ts);
    return ts_to_ns(&ts);
}

static jlong jni_uptime_millis(JNIEnv*, jclass) {
    return virtual_ns(CLOCK_MONOTONIC) / 1000000LL;
}
static jlong jni_uptime_nanos(JNIEnv*, jclass) {
    return virtual_ns(CLOCK_MONOTONIC);
}
static jlong jni_elapsed_millis(JNIEnv*, jclass) {
    return virtual_ns(CLOCK_BOOTTIME) / 1000000LL;
}
static jlong jni_elapsed_nanos(JNIEnv*, jclass) {
    return virtual_ns(CLOCK_BOOTTIME);
}

static bool register_native(JNIEnv* env, const char* class_name, const char* name,
                            const char* sig, void* fn) {
    jclass cls = env->FindClass(class_name);
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    JNINativeMethod method = {name, sig, fn};
    jint rc = env->RegisterNatives(cls, &method, 1);
    env->DeleteLocalRef(cls);
    if (rc != JNI_OK && env->ExceptionCheck()) env->ExceptionClear();
    return rc == JNI_OK;
}

static void install_java_time_hooks(JNIEnv* env) {
    if (g_java_time_hooked || env == nullptr) return;
    bool ok = false;
    ok |= register_native(env, "android/os/SystemClock", "uptimeMillis", "()J",
                          reinterpret_cast<void*>(jni_uptime_millis));
    ok |= register_native(env, "android/os/SystemClock", "uptimeNanos", "()J",
                          reinterpret_cast<void*>(jni_uptime_nanos));
    ok |= register_native(env, "android/os/SystemClock", "elapsedRealtime", "()J",
                          reinterpret_cast<void*>(jni_elapsed_millis));
    ok |= register_native(env, "android/os/SystemClock", "elapsedRealtimeNanos", "()J",
                          reinterpret_cast<void*>(jni_elapsed_nanos));
    g_java_time_hooked = true;
    LOGI("SystemClock hooks installed=%d", ok);
}

#if defined(__aarch64__)
static void* map_trampoline_near(void* target) {
    long page = sysconf(_SC_PAGESIZE);
    auto start = reinterpret_cast<uintptr_t>(target);
    for (int i = 1; i <= 96; i++) {
        auto dist = static_cast<uintptr_t>(i) * 1024u * 1024u;
        uintptr_t hints[2] = {start + dist, start - dist};
        for (uintptr_t hint : hints) {
            hint &= ~(static_cast<uintptr_t>(page) - 1);
            void* mem = mmap(reinterpret_cast<void*>(hint), static_cast<size_t>(page),
                             PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (mem == MAP_FAILED) continue;
            auto delta = static_cast<intptr_t>(reinterpret_cast<uintptr_t>(mem) - start);
            if (delta >= -128 * 1024 * 1024 && delta < 128 * 1024 * 1024 && (delta % 4) == 0) {
                return mem;
            }
            munmap(mem, static_cast<size_t>(page));
        }
    }
    return nullptr;
}

static bool redirect_arm64(void* target, void* hook) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = reinterpret_cast<uintptr_t>(target) & ~(static_cast<uintptr_t>(page) - 1);
    size_t protect_len = static_cast<size_t>(page);
    if ((reinterpret_cast<uintptr_t>(target) & static_cast<uintptr_t>(page - 1)) + 8 > static_cast<uintptr_t>(page)) {
        protect_len = static_cast<size_t>(page) * 2;
    }
    if (mprotect(reinterpret_cast<void*>(page_start), protect_len, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("libc mprotect failed: %s", strerror(errno));
        return false;
    }

    uint32_t first = *reinterpret_cast<uint32_t*>(target);
    bool stub = (first >> 26) == 0x5;
    void* tramp = map_trampoline_near(target);
    if (tramp == nullptr) {
        mprotect(reinterpret_cast<void*>(page_start), protect_len, PROT_READ | PROT_EXEC);
        LOGE("No trampoline near %p", target);
        return false;
    }
    auto* code = reinterpret_cast<uint32_t*>(tramp);
    code[0] = 0xD503245F;             // bti c
    code[1] = 0x58000051;             // ldr x17, #8
    code[2] = 0xD61F0220;             // br x17
    memcpy(code + 3, &hook, sizeof(hook));
    __builtin___clear_cache(reinterpret_cast<char*>(tramp), reinterpret_cast<char*>(tramp) + 24);
    if (mprotect(tramp, static_cast<size_t>(page), PROT_READ | PROT_EXEC) != 0) {
        mprotect(reinterpret_cast<void*>(page_start), protect_len, PROT_READ | PROT_EXEC);
        LOGE("Trampoline mprotect failed: %s", strerror(errno));
        return false;
    }

    auto* site = reinterpret_cast<uint32_t*>(target);
    uint8_t* branch_at = reinterpret_cast<uint8_t*>(stub ? site : site + 1);
    intptr_t off = reinterpret_cast<uint8_t*>(tramp) - branch_at;
    int64_t imm = off >> 2;
    if (imm > 0x1FFFFFF || imm < -0x2000000) {
        mprotect(reinterpret_cast<void*>(page_start), protect_len, PROT_READ | PROT_EXEC);
        LOGE("Trampoline out of branch range");
        return false;
    }
    uint32_t branch = 0x14000000u | (static_cast<uint32_t>(imm) & 0x03FFFFFFu);
    if (stub) {
        site[0] = branch;
        __builtin___clear_cache(reinterpret_cast<char*>(site), reinterpret_cast<char*>(site) + 4);
    } else {
        site[0] = 0xD503245F; // bti c
        site[1] = branch;
        __builtin___clear_cache(reinterpret_cast<char*>(site), reinterpret_cast<char*>(site) + 8);
    }
    mprotect(reinterpret_cast<void*>(page_start), protect_len, PROT_READ | PROT_EXEC);
    return true;
}

static bool unity_insn_needs_reject(uint32_t insn) {
    if ((insn & 0x9F000000u) == 0x90000000u) return false;
    if ((insn & 0x1F000000u) == 0x10000000u) return true;
    if ((insn & 0xFC000000u) == 0x14000000u) return true;
    if ((insn & 0xFC000000u) == 0x94000000u) return true;
    if ((insn & 0xFF000010u) == 0x54000000u) return true;
    if ((insn & 0x7E000000u) == 0x34000000u) return true;
    if ((insn & 0x7E000000u) == 0x36000000u) return true;
    if ((insn & 0x3B000000u) == 0x18000000u) return true;
    return false;
}

static bool unity_relocate_branch(uint32_t insn, uintptr_t old_pc, uintptr_t new_pc, uint32_t* out) {
    uint32_t op = insn & 0xFC000000u;
    if (op != 0x14000000u && op != 0x94000000u) return false;
    int32_t imm = static_cast<int32_t>(insn & 0x03FFFFFFu);
    if (imm & 0x02000000) imm |= ~0x03FFFFFF;
    uintptr_t target = old_pc + (static_cast<intptr_t>(imm) << 2);
    intptr_t neu = (static_cast<intptr_t>(target) - static_cast<intptr_t>(new_pc)) >> 2;
    if (neu > 0x1FFFFFF || neu < -0x2000000) return false;
    *out = op | (static_cast<uint32_t>(neu) & 0x03FFFFFFu);
    return true;
}

static bool unity_relocate_adrp(uint32_t insn, uintptr_t old_pc, uintptr_t new_pc, uint32_t* out) {
    uint32_t op = insn & 0xFC000000u;
    if (op == 0x14000000u || op == 0x94000000u) {
        return unity_relocate_branch(insn, old_pc, new_pc, out);
    }
    if ((insn & 0x9F000000u) != 0x90000000u) {
        *out = insn;
        return true;
    }
    uint32_t immlo = (insn >> 29) & 0x3u;
    uint32_t immhi = (insn >> 5) & 0x7FFFFu;
    int64_t imm = static_cast<int64_t>((immhi << 2) | immlo);
    if (imm & (1LL << 20)) imm |= ~((1LL << 21) - 1);
    int64_t page = (static_cast<int64_t>(old_pc) & ~0xFFFLL) + (imm << 12);
    int64_t new_imm = (page - (static_cast<int64_t>(new_pc) & ~0xFFFLL)) >> 12;
    if (new_imm > 0xFFFFF || new_imm < -0x100000) return false;
    uint32_t u = static_cast<uint32_t>(new_imm) & 0x1FFFFFu;
    *out = 0x90000000u | ((u & 3u) << 29) | (((u >> 2) & 0x7FFFFu) << 5) | (insn & 0x1Fu);
    return true;
}

// Runs the original function body, then returns to the caller. The target entry
// is redirected separately. ADRP in the copied prologue is retargeted.
static bool hook_unity_function(void* target, void* replacement, void** original) {
    if (target == nullptr || replacement == nullptr || original == nullptr) return false;
    auto* src = reinterpret_cast<uint32_t*>(target);
    if (!is_address_readable(src, sizeof(uint32_t) * 4)) return false;
    long page = sysconf(_SC_PAGESIZE);
    void* tramp = map_trampoline_near(target);
    if (tramp == nullptr) return false;
    auto* code = reinterpret_cast<uint32_t*>(tramp);
    code[0] = 0xD503245F; // bti c
    for (int i = 0; i < 4; i++) {
        uintptr_t old_pc = reinterpret_cast<uintptr_t>(target) + static_cast<uintptr_t>(i * 4);
        uintptr_t new_pc = reinterpret_cast<uintptr_t>(tramp) + 4u + static_cast<uintptr_t>(i * 4);
        uint32_t op = src[i] & 0xFC000000u;
        bool branch = op == 0x14000000u || op == 0x94000000u;
        if ((!branch && unity_insn_needs_reject(src[i])) ||
            !unity_relocate_adrp(src[i], old_pc, new_pc, &code[1 + i])) {
            munmap(tramp, static_cast<size_t>(page));
            return false;
        }
    }
    uint8_t* branch_at = reinterpret_cast<uint8_t*>(code + 5);
    intptr_t back = (reinterpret_cast<uint8_t*>(target) + 16) - branch_at;
    int64_t imm = back >> 2;
    if (imm > 0x1FFFFFF || imm < -0x2000000) {
        munmap(tramp, static_cast<size_t>(page));
        return false;
    }
    code[5] = 0x14000000u | (static_cast<uint32_t>(imm) & 0x03FFFFFFu);
    __builtin___clear_cache(reinterpret_cast<char*>(tramp), reinterpret_cast<char*>(tramp) + 24);
    if (mprotect(tramp, static_cast<size_t>(page), PROT_READ | PROT_EXEC) != 0) {
        munmap(tramp, static_cast<size_t>(page));
        return false;
    }
    *original = tramp;
    if (!redirect_arm64(target, replacement)) {
        *original = nullptr;
        munmap(tramp, static_cast<size_t>(page));
        return false;
    }
    return true;
}
#endif

static void* libc_symbol(const char* name) {
    void* libc = dlopen("libc.so", RTLD_NOW);
    void* sym = libc != nullptr ? dlsym(libc, name) : nullptr;
    if (sym == nullptr) sym = dlsym(RTLD_DEFAULT, name);
    return sym;
}

static void install_inline_time_hooks() {
    struct timespec probe{};
    if (!g_raw_clock_ok) {
        g_raw_clock_ok = syscall(__NR_clock_gettime, CLOCK_MONOTONIC, &probe) == 0;
        LOGI("raw clock_gettime syscall %s", g_raw_clock_ok ? "ok" : "unavailable");
    }
    if (!g_raw_sleep_ok) {
        struct timespec zero{};
        g_raw_sleep_ok = syscall(__NR_nanosleep, &zero, nullptr) == 0;
    }
#if defined(__aarch64__)
    if (!g_raw_clock_ok) return;
    if (!g_inline_clock) {
        void* target = libc_symbol("clock_gettime");
        if (target != nullptr && redirect_arm64(target, reinterpret_cast<void*>(hooked_clock_gettime))) {
            g_inline_clock = true;
            LOGI("Inlined clock_gettime at %p", target);
        } else {
            LOGE("Inline clock_gettime hook failed");
        }
    }
    if (!g_inline_sleep && g_raw_sleep_ok) {
        void* target = libc_symbol("nanosleep");
        if (target != nullptr && redirect_arm64(target, reinterpret_cast<void*>(hooked_nanosleep))) {
            g_inline_sleep = true;
            LOGI("Inlined nanosleep at %p", target);
        } else {
            LOGE("Inline nanosleep hook failed");
        }
    }
#else
    LOGI("Inline time hook is arm64-only on this build");
#endif
}

#if !defined(__aarch64__)
static bool hook_unity_function(void*, void*, void**) { return false; }
#endif

// Unity games ignore libc clock hooks. Time.timeScale is what Time.deltaTime,
// animation, and PrimeTween (scaled mode) actually multiply.
static std::atomic<float> g_unity_scale{1.f};
static std::atomic<bool> g_unity_forced{false};

using unity_set_fn = void (*)(float);

static void* g_il2cpp = nullptr;
static void* (*g_thread_current)() = nullptr;
static void* (*g_resolve_icall)(const char*) = nullptr;
static unity_set_fn g_set_time_scale = nullptr;
static unity_set_fn g_set_max_delta = nullptr;
static float g_logged_unity_scale = -1.f;
static float g_applied_scale = -1.f;
static int64_t g_last_apply_ns = 0;
static std::atomic<bool> g_unity_restore{false};
static thread_local int g_unity_apply_depth = 0;

struct Il2CppLib {
    char path[512];
};

static int find_il2cpp(struct dl_phdr_info* info, size_t, void* data) {
    auto* out = reinterpret_cast<Il2CppLib*>(data);
    if (info->dlpi_name != nullptr && strstr(info->dlpi_name, "libil2cpp.so") != nullptr) {
        strncpy(out->path, info->dlpi_name, sizeof(out->path) - 1);
        out->path[sizeof(out->path) - 1] = '\0';
        return 1;
    }
    return 0;
}

static void* open_il2cpp() {
    void* lib = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (lib != nullptr) return lib;
    Il2CppLib found{};
    dl_iterate_phdr(find_il2cpp, &found);
    if (found.path[0] == '\0') return nullptr;
    lib = dlopen(found.path, RTLD_NOW | RTLD_NOLOAD);
    if (lib == nullptr) lib = dlopen(found.path, RTLD_NOW);
    if (lib != nullptr) LOGI("libil2cpp opened from %s", found.path);
    return lib;
}

static void* resolve_icall(const char* name) {
    if (g_resolve_icall == nullptr || name == nullptr) return nullptr;
    return g_resolve_icall(name);
}

static bool g_unity_probe_installed = false;
static int (*g_real_poll)(int, int*, int*, void**) = nullptr;
static unsigned int (*g_real_egl_swap)(void*, void*) = nullptr;
static int (*g_real_vk_present)(void*, const void*) = nullptr;

// Called from the game's own frame points. il2cpp exports crash at 0x135 on a
// thread Unity has not attached, so set_timeScale runs only after
// il2cpp_thread_current() returns a thread.
static void unity_apply_on_game_thread() {
    bool forced = g_unity_forced.load(std::memory_order_relaxed);
    bool restore = g_unity_restore.load(std::memory_order_relaxed);
    if ((!forced && !restore) || g_unity_apply_depth > 0) return;
    g_unity_apply_depth++;

    if (g_il2cpp == nullptr) g_il2cpp = open_il2cpp();
    if (g_il2cpp != nullptr && g_thread_current == nullptr) {
        g_thread_current = reinterpret_cast<void* (*)()>(dlsym(g_il2cpp, "il2cpp_thread_current"));
    }
    if (g_il2cpp == nullptr || g_thread_current == nullptr || g_thread_current() == nullptr) {
        static bool logged = false;
        if (!logged) {
            LOGW("Unity frame thread is not attached to il2cpp (current=%p)",
                 reinterpret_cast<void*>(g_thread_current));
            logged = true;
        }
        g_unity_apply_depth--;
        return;
    }
    if (g_resolve_icall == nullptr) {
        g_resolve_icall = reinterpret_cast<void* (*)(const char*)>(
            dlsym(g_il2cpp, "il2cpp_resolve_icall"));
    }
    if (g_resolve_icall != nullptr && g_set_time_scale == nullptr) {
        g_set_time_scale = reinterpret_cast<unity_set_fn>(
            resolve_icall("UnityEngine.Time::set_timeScale(System.Single)"));
        if (g_set_time_scale == nullptr) {
            g_set_time_scale = reinterpret_cast<unity_set_fn>(
                resolve_icall("UnityEngine.Time::set_timeScale(single)"));
        }
        g_set_max_delta = reinterpret_cast<unity_set_fn>(
            resolve_icall("UnityEngine.Time::set_maximumDeltaTime(System.Single)"));
        if (g_set_time_scale == nullptr) {
            LOGW("Unity Time.set_timeScale was not found");
        }
    }

    float scale = forced ? g_unity_scale.load(std::memory_order_relaxed) : 1.f;
    struct timespec now_ts{};
    int64_t now = 0;
    if (syscall(__NR_clock_gettime, CLOCK_MONOTONIC, &now_ts) == 0) {
        now = static_cast<int64_t>(now_ts.tv_sec) * 1000000000LL + now_ts.tv_nsec;
    }
    if (g_applied_scale == scale && now != 0 && now - g_last_apply_ns < 8000000LL) {
        g_unity_apply_depth--;
        return;
    }
    if (g_set_time_scale != nullptr) g_set_time_scale(scale);
    if (g_set_max_delta != nullptr) {
        float cap = scale > 1.f ? 0.333f * scale : 0.333f;
        if (cap > 1.f) cap = 1.f;
        g_set_max_delta(cap);
    }
    if (g_applied_scale != scale) {
        LOGI("Unity timeScale set to %.2f", scale);
        g_applied_scale = scale;
    }
    g_last_apply_ns = now;
    if (!forced) g_unity_restore.store(false, std::memory_order_relaxed);
    g_unity_apply_depth--;
}

static int hooked_unity_clock(clockid_t clk, struct timespec* ts) {
    unity_apply_on_game_thread();
    if (ts == nullptr) return -1;
    return syscall(__NR_clock_gettime, clk, ts) == 0 ? 0 : -1;
}

static int hooked_unity_poll(int timeout, int* fd, int* events, void** data) {
    unity_apply_on_game_thread();
    if (g_real_poll == nullptr) return -1;
    return g_real_poll(timeout, fd, events, data);
}

static unsigned int hooked_unity_swap(void* display, void* surface) {
    unity_apply_on_game_thread();
    if (g_real_egl_swap == nullptr) return 0;
    return g_real_egl_swap(display, surface);
}

static int hooked_unity_present(void* queue, const void* info) {
    unity_apply_on_game_thread();
    if (g_real_vk_present == nullptr) return -1;
    return g_real_vk_present(queue, info);
}

static void arm_game_symbol(const char* symbol, void* hook, void** orig) {
    g_one_symbol = symbol;
    g_one_hook = hook;
    g_one_orig = orig;
    g_one_symbol_only = true;
    dl_iterate_phdr(install_time_hooks_callback, nullptr);
    g_one_symbol_only = false;
    g_one_symbol = nullptr;
    g_one_hook = nullptr;
    g_one_orig = nullptr;
}

static void install_unity_probe() {
    if (g_unity_probe_installed) return;
    resolve_time_symbols();
    g_clock_probe_hook = reinterpret_cast<void*>(hooked_unity_clock);
    g_clock_probe_orig = reinterpret_cast<void**>(&g_unity_clock_orig);
    g_clock_probe_only = true;
    dl_iterate_phdr(install_time_hooks_callback, nullptr);
    g_clock_probe_only = false;
    g_clock_probe_hook = nullptr;
    g_clock_probe_orig = nullptr;
    arm_game_symbol("ALooper_pollOnce", reinterpret_cast<void*>(hooked_unity_poll),
                    reinterpret_cast<void**>(&g_real_poll));
    arm_game_symbol("eglSwapBuffers", reinterpret_cast<void*>(hooked_unity_swap),
                    reinterpret_cast<void**>(&g_real_egl_swap));
    arm_game_symbol("vkQueuePresentKHR", reinterpret_cast<void*>(hooked_unity_present),
                    reinterpret_cast<void**>(&g_real_vk_present));
    g_unity_probe_installed = true;
    LOGI("Unity speed hooks armed");
}

struct Il2Seg {
    uintptr_t start;
    uintptr_t end;
    bool exec;
    bool write;
};

static Il2Seg g_il2_segs[48];
static int g_il2_seg_count = 0;

static int collect_il2cpp_segs(struct dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name == nullptr || strstr(info->dlpi_name, "libil2cpp.so") == nullptr) return 0;
    g_il2_seg_count = 0;
    for (int i = 0; i < info->dlpi_phnum && g_il2_seg_count < 48; i++) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || ph.p_memsz == 0) continue;
        Il2Seg& seg = g_il2_segs[g_il2_seg_count++];
        seg.start = static_cast<uintptr_t>(info->dlpi_addr) + ph.p_vaddr;
        seg.end = seg.start + ph.p_memsz;
        seg.exec = (ph.p_flags & PF_X) != 0;
        seg.write = (ph.p_flags & PF_W) != 0;
    }
    return g_il2_seg_count > 0 ? 1 : 0;
}

static bool il2_exec(uintptr_t addr) {
    if ((addr & 3u) != 0) return false;
    for (int i = 0; i < g_il2_seg_count; i++) {
        if (g_il2_segs[i].exec && addr >= g_il2_segs[i].start && addr < g_il2_segs[i].end) {
            return true;
        }
    }
    return false;
}

// The icall name is a NUL-terminated literal. The runtime map stores that
// pointer, and the native function sits in the next pointer-sized slot.
static int find_cstrs(const char* needle, uintptr_t* out, int cap) {
    size_t n = strlen(needle);
    if (n == 0 || cap <= 0) return 0;
    const uintptr_t page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    int found = 0;
    unsigned char present = 0;
    for (int i = 0; i < g_il2_seg_count && found < cap; i++) {
        uintptr_t p = g_il2_segs[i].start & ~(page - 1);
        while (p < g_il2_segs[i].end && found < cap) {
            if (mincore(reinterpret_cast<void*>(p), page, &present) != 0) {
                p += page;
                continue;
            }
            uintptr_t from = p < g_il2_segs[i].start ? g_il2_segs[i].start : p;
            uintptr_t to = p + page;
            if (to > g_il2_segs[i].end) to = g_il2_segs[i].end;
            const char* cursor = reinterpret_cast<const char*>(from);
            size_t left = to > from ? to - from : 0;
            while (left >= n && found < cap) {
                void* hit = memmem(cursor, left, needle, n);
                if (hit == nullptr) break;
                auto* text = reinterpret_cast<const char*>(hit);
                size_t used = static_cast<size_t>(text - cursor);
                if (used + n < left && text[n] == '\0') {
                    out[found++] = reinterpret_cast<uintptr_t>(text);
                }
                size_t step = used + 1;
                if (step > left) break;
                cursor += step;
                left -= step;
            }
            p += page;
        }
    }
    return found;
}

struct RwRange {
    uintptr_t start;
    uintptr_t end;
};

static RwRange g_rw[2048];
static int g_rw_count = 0;

static bool load_rw_ranges() {
    g_rw_count = 0;
    int fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC));
    if (fd < 0) return false;
    std::string buf;
    char tmp[4096];
    while (buf.size() < 8u * 1024u * 1024u) {
        long n = syscall(__NR_read, fd, tmp, sizeof(tmp));
        if (n <= 0) break;
        buf.append(tmp, tmp + n);
    }
    syscall(__NR_close, fd);

    size_t pos = 0;
    while (pos < buf.size() && g_rw_count < 2048) {
        size_t nl = buf.find('\n', pos);
        if (nl == std::string::npos) nl = buf.size();
        if (nl > pos) {
            char line[768];
            size_t len = nl - pos;
            if (len >= sizeof(line)) len = sizeof(line) - 1;
            memcpy(line, buf.c_str() + pos, len);
            line[len] = '\0';
            uintptr_t start = 0;
            uintptr_t end = 0;
            char perms[5] = {};
            if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s", &start, &end, perms) == 3 &&
                perms[0] == 'r' && perms[1] == 'w' && end > start &&
                end - start <= 256u * 1024u * 1024u &&
                strstr(line, "/dev/") == nullptr) {
                g_rw[g_rw_count].start = start;
                g_rw[g_rw_count].end = end;
                g_rw_count++;
            }
        }
        pos = nl + 1;
    }
    return g_rw_count > 0;
}

static int g_vm_read = 0;

static bool copy_mem(uintptr_t addr, void* dst, size_t len) {
    if (len == 0) return false;
    if (g_vm_read < 0) {
        if (!is_address_readable(reinterpret_cast<void*>(addr), len)) return false;
        memcpy(dst, reinterpret_cast<const void*>(addr), len);
        return true;
    }
    struct iovec local{};
    struct iovec remote{};
    local.iov_base = dst;
    local.iov_len = len;
    remote.iov_base = reinterpret_cast<void*>(addr);
    remote.iov_len = len;
    if (process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == static_cast<ssize_t>(len)) {
        g_vm_read = 1;
        return true;
    }
    if (g_vm_read == 0 && (errno == EPERM || errno == ENOSYS)) {
        g_vm_read = -1;
        LOGW("process_vm_readv unavailable (%d)", errno);
        return copy_mem(addr, dst, len);
    }
    return false;
}

static int icall_prologue_rank(uintptr_t fn) {
    if (!is_address_readable(reinterpret_cast<void*>(fn), sizeof(uint32_t))) return 0;
    uint32_t word = *reinterpret_cast<const uint32_t*>(fn);
    if (word == 0xD503233Fu || word == 0xD503237Fu) return 3;
    uint32_t stp = word & 0xFFC00000u;
    if (stp == 0xA9800000u || stp == 0xA9000000u || stp == 0xA8800000u) return 2;
    if ((word & 0xFF0003FFu) == 0xD10003FFu) return 2;
    return 1;
}

static void* find_icall_fn(const char* name) {
    uintptr_t keys[8];
    int key_count = find_cstrs(name, keys, 8);
    if (key_count == 0) {
        LOGW("Unity icall string missing: %s", name);
        return nullptr;
    }
    LOGI("Unity icall string %s at %p", name, reinterpret_cast<void*>(keys[0]));
    // The icall map nodes are malloc'd, so they are not inside libil2cpp's
    // own writable segment. Search every readable/writable mapping.
    if (!load_rw_ranges()) {
        LOGW("Unity icall pointer scan has no writable ranges");
        return nullptr;
    }
    const uintptr_t page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    if (page == 0) return nullptr;
    static unsigned char* page_buf = nullptr;
    static size_t page_cap = 0;
    if (page_cap < page) {
        free(page_buf);
        page_buf = static_cast<unsigned char*>(malloc(page));
        page_cap = page_buf != nullptr ? page : 0;
    }
    if (page_buf == nullptr) return nullptr;
    void* found = nullptr;
    int hits = 0;
    int scored = -1;
    LOGI("Unity icall pointer scan %d ranges for %s", g_rw_count, name);
    for (int i = 0; i < g_rw_count; i++) {
        uintptr_t pg = g_rw[i].start & ~(page - 1);
        const uintptr_t end = g_rw[i].end;
        for (; pg < end; pg += page) {
            uintptr_t to = pg + page;
            if (to > end) to = end;
            size_t span = static_cast<size_t>(to - pg);
            if (!copy_mem(pg, page_buf, span)) continue;
            uintptr_t from = pg < g_rw[i].start ? g_rw[i].start : pg;
            from = (from + 7u) & ~static_cast<uintptr_t>(7);
            for (uintptr_t p = from; p + 8 <= to; p += 8) {
                uintptr_t slot = 0;
                memcpy(&slot, page_buf + (p - pg), sizeof(slot));
                bool key = false;
                for (int k = 0; k < key_count; k++) {
                    if (slot == keys[k]) {
                        key = true;
                        break;
                    }
                }
                if (!key) continue;
                for (uintptr_t off = 8; off <= 24; off += 8) {
                    uintptr_t fn_addr = p + off;
                    uintptr_t fn = 0;
                    if (fn_addr >= pg && fn_addr + 8 <= to) {
                        memcpy(&fn, page_buf + (fn_addr - pg), sizeof(fn));
                    } else if (!copy_mem(fn_addr, &fn, sizeof(fn))) {
                        break;
                    }
                    if (!il2_exec(fn)) continue;
                    int rank = icall_prologue_rank(fn);
                    hits++;
                    if (found == nullptr || rank > scored) {
                        found = reinterpret_cast<void*>(fn);
                        scored = rank;
                    }
                    break;
                }
            }
        }
    }
    if (found == nullptr) LOGW("Unity icall pointer missing: %s", name);
    else LOGI("Unity icall %s at %p (%d)", name, found, hits);
    return found;
}

using unity_get_fn = float (*)();
static unity_get_fn g_orig_delta = nullptr;
static unity_get_fn g_orig_fixed = nullptr;
static bool g_icall_done = false;
static void** g_delta_slot = nullptr;
static void** g_set_slot = nullptr;
static void* (*g_stub_resolve)(const char*) = nullptr;
static const char* g_set_name = nullptr;
static bool g_stubs_found = false;
static bool g_resolve_tried = false;

static bool g_max_delta_tried = false;

static bool on_unity_main_thread() {
    return static_cast<pid_t>(syscall(__NR_gettid)) == getpid();
}

static float scaled_delta(unity_get_fn orig) {
    if (orig == nullptr) return 0.f;
    bool forced = g_unity_forced.load(std::memory_order_relaxed);
    bool restore = g_unity_restore.load(std::memory_order_relaxed);
    float scale = forced ? g_unity_scale.load(std::memory_order_relaxed) : 1.f;
    // TimeManager is main-thread only. Setting it from a job thread is what
    // aborts ART (mremap 512MB) once the scale goes above 2x.
    if ((forced || restore) && g_unity_apply_depth == 0 && on_unity_main_thread()) {
        g_unity_apply_depth++;
        if (g_set_time_scale == nullptr && g_set_slot != nullptr &&
            is_address_readable(g_set_slot, sizeof(void*))) {
            void* setter = __atomic_load_n(g_set_slot, __ATOMIC_ACQUIRE);
            if (setter != nullptr) g_set_time_scale = reinterpret_cast<unity_set_fn>(setter);
        }
        if (!g_resolve_tried && g_set_time_scale == nullptr &&
            g_stub_resolve != nullptr && g_set_name != nullptr) {
            g_resolve_tried = true;
            void* setter = g_stub_resolve(g_set_name);
            if (setter != nullptr) {
                g_set_time_scale = reinterpret_cast<unity_set_fn>(setter);
                LOGI("Unity set_timeScale resolved at %p", setter);
            } else {
                LOGW("Unity set_timeScale resolve returned null");
            }
        }
        if (!g_max_delta_tried && g_stub_resolve != nullptr) {
            g_max_delta_tried = true;
            void* cap_fn = g_stub_resolve("UnityEngine.Time::set_maximumDeltaTime(System.Single)");
            if (cap_fn != nullptr) {
                g_set_max_delta = reinterpret_cast<unity_set_fn>(cap_fn);
                LOGI("Unity set_maximumDeltaTime resolved at %p", cap_fn);
            }
        }
        if (g_set_time_scale == nullptr) {
            g_unity_apply_depth--;
            float value = orig();
            if (forced && scale != 1.f) value *= scale;
            return value;
        }
        struct timespec now_ts{};
        int64_t now = 0;
        if (syscall(__NR_clock_gettime, CLOCK_MONOTONIC, &now_ts) == 0) {
            now = static_cast<int64_t>(now_ts.tv_sec) * 1000000000LL + now_ts.tv_nsec;
        }
        if (g_applied_scale != scale || now == 0 || now - g_last_apply_ns >= 200000000LL) {
            g_set_time_scale(scale);
            if (g_set_max_delta != nullptr) {
                // One frame may advance about one scaled tick, not a third of
                // a second. A 5x or 10x hitch was simulating enough gameplay
                // to make the garbage collector abort.
                float cap = 0.021f * scale;
                if (cap < 0.05f) cap = 0.05f;
                if (cap > 0.22f) cap = 0.22f;
                if (scale <= 1.f) cap = 0.333f;
                g_set_max_delta(cap);
            }
            if (g_applied_scale != scale) {
                LOGI("Unity timeScale set to %.2f", scale);
                g_applied_scale = scale;
            }
            g_last_apply_ns = now;
            if (!forced) g_unity_restore.store(false, std::memory_order_relaxed);
        }
        g_unity_apply_depth--;
    }
    float value = orig();
    if (g_set_time_scale == nullptr && forced && scale != 1.f) value *= scale;
    return value;
}

static float hooked_delta() { return scaled_delta(g_orig_delta); }

static bool decode_adrp_page(uint32_t insn, uintptr_t pc, int* rd, uintptr_t* page) {
    if ((insn & 0x9F000000u) != 0x90000000u) return false;
    *rd = static_cast<int>(insn & 31u);
    int64_t imm = (static_cast<int64_t>((insn >> 5) & 0x7FFFFu) << 2) | ((insn >> 29) & 3u);
    if (imm & (1LL << 20)) imm |= ~((1LL << 21) - 1);
    *page = (pc & ~static_cast<uintptr_t>(0xFFF)) +
            static_cast<uintptr_t>(static_cast<uint64_t>(imm) << 12);
    return true;
}

// Generated il2cpp stub:
//   adrp xN, cache_page
//   ldr  x0, [xN, #slot]
//   cbnz x0, hit
//   adrp x0, name          <-- returned pc
//   add  x0, x0, #off
//   bl   resolve
static uintptr_t find_name_adrp(uintptr_t target) {
    const uintptr_t page_size = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    if (page_size == 0) return 0;
    const uintptr_t want_page = target & ~static_cast<uintptr_t>(0xFFF);
    const uint32_t want_off = static_cast<uint32_t>(target & 0xFFF);
    for (int s = 0; s < g_il2_seg_count; s++) {
        if (!g_il2_segs[s].exec) continue;
        uintptr_t pg = g_il2_segs[s].start & ~(page_size - 1);
        for (; pg < g_il2_segs[s].end; pg += page_size) {
            if (!is_address_readable(reinterpret_cast<void*>(pg), sizeof(uint32_t))) continue;
            uintptr_t from = pg < g_il2_segs[s].start ? g_il2_segs[s].start : pg;
            from = (from + 3u) & ~static_cast<uintptr_t>(3);
            uintptr_t to = pg + page_size;
            if (to > g_il2_segs[s].end) to = g_il2_segs[s].end;
            for (uintptr_t pc = from; pc + 8 <= to; pc += 4) {
                int rd = 0;
                uintptr_t adrp_page = 0;
                uint32_t insn = *reinterpret_cast<const uint32_t*>(pc);
                if (!decode_adrp_page(insn, pc, &rd, &adrp_page) || adrp_page != want_page) continue;
                uint32_t add = *reinterpret_cast<const uint32_t*>(pc + 4);
                if ((add & 0xFFC00000u) != 0x91000000u) continue;
                if (static_cast<int>((add >> 5) & 31u) != rd) continue;
                uint32_t imm12 = (add >> 10) & 0xFFFu;
                if ((add >> 22) & 1u) imm12 <<= 12;
                if (imm12 == want_off) return pc;
            }
        }
    }
    return 0;
}

static void** cache_slot_for(uintptr_t name_adrp) {
    if (name_adrp < 12 || !is_address_readable(reinterpret_cast<void*>(name_adrp - 12), 16)) return nullptr;
    int rd = 0;
    uintptr_t cache_page = 0;
    uint32_t adrp = *reinterpret_cast<const uint32_t*>(name_adrp - 12);
    uint32_t ldr = *reinterpret_cast<const uint32_t*>(name_adrp - 8);
    if (!decode_adrp_page(adrp, name_adrp - 12, &rd, &cache_page)) return nullptr;
    if ((ldr & 0xFFC00000u) != 0xF9400000u) return nullptr;
    if (static_cast<int>((ldr >> 5) & 31u) != rd) return nullptr;
    uint32_t off = ((ldr >> 10) & 0xFFFu) * 8u;
    return reinterpret_cast<void**>(cache_page + off);
}

static void* (*resolve_for(uintptr_t name_adrp))(const char*) {
    if (!is_address_readable(reinterpret_cast<void*>(name_adrp + 8), 4)) return nullptr;
    uint32_t bl = *reinterpret_cast<const uint32_t*>(name_adrp + 8);
    if ((bl & 0xFC000000u) != 0x94000000u) return nullptr;
    int32_t imm = static_cast<int32_t>(bl & 0x03FFFFFFu);
    if (imm & 0x02000000) imm |= ~0x03FFFFFF;
    uintptr_t dest = (name_adrp + 8) + (static_cast<intptr_t>(imm) << 2);
    return reinterpret_cast<void* (*)(const char*)>(dest);
}

static void install_unity_icall_hook() {
    if (g_icall_done) return;
    if (!g_stubs_found) {
        g_il2_seg_count = 0;
        dl_iterate_phdr(collect_il2cpp_segs, nullptr);
        if (g_il2_seg_count == 0) {
            LOGW("libil2cpp segments were not mapped yet");
            return;
        }
        uintptr_t names[4];
        int name_count = find_cstrs("UnityEngine.Time::get_deltaTime()", names, 4);
        if (name_count == 0) {
            LOGW("Unity get_deltaTime string missing");
            g_stubs_found = true;
            g_icall_done = true;
            return;
        }
        uintptr_t delta_adrp = find_name_adrp(names[0]);
        g_delta_slot = cache_slot_for(delta_adrp);
        g_stub_resolve = delta_adrp != 0 ? resolve_for(delta_adrp) : nullptr;
        LOGI("Unity delta stub %p slot %p", reinterpret_cast<void*>(delta_adrp),
             static_cast<void*>(g_delta_slot));

        int set_count = find_cstrs("UnityEngine.Time::set_timeScale(System.Single)", names, 4);
        if (set_count > 0) {
            g_set_name = reinterpret_cast<const char*>(names[0]);
            uintptr_t set_adrp = find_name_adrp(names[0]);
            g_set_slot = cache_slot_for(set_adrp);
            if (g_stub_resolve == nullptr) g_stub_resolve = resolve_for(set_adrp);
            LOGI("Unity set_timeScale stub %p slot %p", reinterpret_cast<void*>(set_adrp),
                 static_cast<void*>(g_set_slot));
        } else {
            LOGW("Unity set_timeScale string missing");
        }
        g_stubs_found = true;
    }
    if (g_delta_slot == nullptr || !is_address_readable(g_delta_slot, sizeof(void*))) {
        LOGW("Unity delta slot was not found");
        g_icall_done = true;
        return;
    }
    void* orig = __atomic_load_n(g_delta_slot, __ATOMIC_ACQUIRE);
    if (orig == nullptr) {
        LOGW("Unity delta slot is empty, will retry");
        return;
    }
    if (orig == reinterpret_cast<void*>(hooked_delta)) {
        g_icall_done = true;
        return;
    }
    if (!is_address_readable(orig, sizeof(uint32_t))) {
        LOGW("Unity delta target %p is not readable", orig);
        g_icall_done = true;
        return;
    }
    g_orig_delta = reinterpret_cast<unity_get_fn>(orig);
    __atomic_store_n(g_delta_slot, reinterpret_cast<void*>(hooked_delta), __ATOMIC_RELEASE);
    if (g_set_slot != nullptr && is_address_readable(g_set_slot, sizeof(void*)) &&
        g_set_time_scale == nullptr) {
        void* setter = __atomic_load_n(g_set_slot, __ATOMIC_ACQUIRE);
        if (setter != nullptr) g_set_time_scale = reinterpret_cast<unity_set_fn>(setter);
    }
    g_icall_done = true;
    LOGI("Unity get_deltaTime hooked orig %p setter %p", orig,
         reinterpret_cast<void*>(g_set_time_scale));
}

static bool apply_unity_timescale(float desired) {
    if (desired < 0.05f) desired = 0.05f;
    if (desired > 20.f) desired = 20.f;
    // Never call il2cpp exports here. This thread is not attached, and those
    // calls are the SIGSEGV at 0x135. The icall scan only reads memory.
    g_unity_scale.store(desired);
    if (desired != 1.f) {
        g_unity_forced.store(true);
        install_unity_icall_hook();
        install_unity_probe();
    } else {
        if (g_unity_forced.exchange(false)) g_unity_restore.store(true);
    }
    if (g_logged_unity_scale != desired) {
        LOGI("Unity speed %.2f armed", desired);
        g_logged_unity_scale = desired;
    }
    return true;
}

static void apply_game_speed_now(float scale, bool unity, bool clock, bool sleep) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        reanchor_clock(scale, clock);
        g_scale_sleep.store(sleep);
        if (clock || sleep) install_time_hooks();
    }
    if (unity || g_unity_forced.load()) {
        apply_unity_timescale(unity ? scale : 1.f);
    }
    int flags = (unity ? 1 : 0) | (clock ? 2 : 0) | (sleep ? 4 : 0);
    static float logged_scale = -1.f;
    static int logged_flags = -1;
    if (logged_scale != scale || logged_flags != flags) {
        LOGI("Game speed %.2f unity=%d clock=%d sleep=%d", scale, unity, clock, sleep);
        logged_scale = scale;
        logged_flags = flags;
    }
}

// Installing the Unity hook resolves il2cpp and rewrites libil2cpp code.
// Doing that on the click thread freezes the app: the main looper cannot
// run, and Unity may be waiting on it while mprotect waits for Unity.
static pthread_mutex_t g_speed_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_speed_cv = PTHREAD_COND_INITIALIZER;
static bool g_speed_worker_started = false;
static bool g_speed_dirty = false;
static float g_speed_scale = 1.f;
static bool g_speed_unity = false;
static bool g_speed_clock = false;
static bool g_speed_sleep = false;

static void* game_speed_worker(void*) {
    for (;;) {
        float scale;
        bool unity;
        bool clock;
        bool sleep;
        pthread_mutex_lock(&g_speed_mu);
        while (!g_speed_dirty) pthread_cond_wait(&g_speed_cv, &g_speed_mu);
        scale = g_speed_scale;
        unity = g_speed_unity;
        clock = g_speed_clock;
        sleep = g_speed_sleep;
        g_speed_dirty = false;
        pthread_mutex_unlock(&g_speed_mu);
        apply_game_speed_now(scale, unity, clock, sleep);
    }
    return nullptr;
}

static jboolean apply_game_speed(JNIEnv* env, jfloat scale, jboolean unity, jboolean clock, jboolean sleep) {
    (void)env;
    if (scale < 0.05f) scale = 0.05f;
    if (scale > 20.f) scale = 20.f;
    if (unity == JNI_TRUE) {
        g_unity_scale.store(scale);
        g_unity_forced.store(scale != 1.f);
    } else if (g_unity_forced.load()) {
        g_unity_scale.store(1.f);
        g_unity_forced.store(false);
    }

    pthread_mutex_lock(&g_speed_mu);
    g_speed_scale = scale;
    g_speed_unity = unity == JNI_TRUE;
    g_speed_clock = clock == JNI_TRUE;
    g_speed_sleep = sleep == JNI_TRUE;
    g_speed_dirty = true;
    if (!g_speed_worker_started) {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        pthread_t thread;
        int rc = pthread_create(&thread, &attr, game_speed_worker, nullptr);
        pthread_attr_destroy(&attr);
        if (rc != 0) {
            g_speed_dirty = false;
            pthread_mutex_unlock(&g_speed_mu);
            LOGW("Game speed worker failed to start");
            return JNI_FALSE;
        }
        g_speed_worker_started = true;
    }
    pthread_cond_signal(&g_speed_cv);
    pthread_mutex_unlock(&g_speed_mu);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_GameSpeed_nativeSetGameSpeed(
    JNIEnv* env, jobject /*thiz*/, jfloat scale, jboolean clock, jboolean sleep)
{
    return apply_game_speed(env, scale, JNI_FALSE, clock, sleep);
}

JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeApplyGameSpeed(
    JNIEnv* env, jclass /*cls*/, jfloat scale, jboolean unity, jboolean clock, jboolean sleep)
{
    return apply_game_speed(env, scale, unity, clock, sleep);
}

// ==================== Runtime.nativeLoad Hook ====================
//
// Jiagu/360-protected apps call Runtime.load0() which delegates to
// Runtime.nativeLoad(String filename, ClassLoader loader, Class caller).
// In the virtual environment, the "caller" Class argument becomes null
// because Jiagu's native code calls System.loadLibrary via JNI reflection
// from a thread where the caller class cannot be resolved.
//
// ART's CheckJNI mode then calls GetObjectArrayElement on NULL (the
// ProtectionDomain array derived from the null caller) → SIGABRT.
//
// Fix: Hook Runtime.nativeLoad at JNI registration level via RegisterNatives.
// If caller==null, synthesize a non-null Class from the classLoader or use
// java.lang.Runtime as a fallback caller.

// Original native implementation saved via method registration replacement
static void* g_orig_nativeLoad_fn = nullptr;

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);

static jstring hooked_nativeLoad(JNIEnv* env, jclass runtimeClass,
                                  jstring filename, jobject classLoader, jclass caller) {
    if (caller == nullptr) {
        LOGI("Runtime.nativeLoad intercepted with null caller — fixing");

        // Strategy 1: Derive a class from the classLoader
        if (classLoader != nullptr) {
            // Try ClassLoader.loadClass("java.lang.Object") to get a valid Class
            jclass clsLoaderClass = env->GetObjectClass(classLoader);
            if (clsLoaderClass != nullptr) {
                jmethodID loadClassMethod = env->GetMethodID(
                    clsLoaderClass, "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
                if (loadClassMethod != nullptr) {
                    jstring objClassName = env->NewStringUTF("java.lang.Object");
                    if (objClassName != nullptr) {
                        // Clear any pending exception before calling loadClass
                        if (env->ExceptionCheck()) env->ExceptionClear();

                        jobject loadedClass = env->CallObjectMethod(
                            classLoader, loadClassMethod, objClassName);
                        env->DeleteLocalRef(objClassName);

                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        } else if (loadedClass != nullptr) {
                            caller = static_cast<jclass>(loadedClass);
                            LOGI("Runtime.nativeLoad: fixed null caller via classLoader → java.lang.Object");
                        }
                    }
                }
                env->DeleteLocalRef(clsLoaderClass);
            }
        }

        // Strategy 2: Fallback — use java.lang.Runtime itself as the caller
        if (caller == nullptr) {
            caller = runtimeClass;
            LOGI("Runtime.nativeLoad: fixed null caller → java.lang.Runtime (fallback)");
        }
    }

    // Call original implementation
    if (g_orig_nativeLoad_fn != nullptr) {
        return ((NativeLoadFn)g_orig_nativeLoad_fn)(env, runtimeClass, filename, classLoader, caller);
    }

    // If we somehow don't have the original, try dlsym as last resort
    LOGE("Runtime.nativeLoad: no original function pointer — cannot forward call");
    return nullptr;
}

/**
 * Install the Runtime.nativeLoad hook using RegisterNatives.
 *
 * Approach:
 * 1. Find java.lang.Runtime class
 * 2. Save original nativeLoad function pointer via GetMethodID + JNI internal lookup
 * 3. Register our hooked version via RegisterNatives
 */
static bool installNativeLoadHook(JNIEnv* env) {
    // Find java.lang.Runtime
    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr) {
        LOGE("installNativeLoadHook: cannot find java/lang/Runtime");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    // Try to get the original native function pointer from libart.so
    // The symbol name varies by ART version but we try the common ones
    void* libart = dlopen("libart.so", RTLD_NOLOAD);
    if (libart == nullptr) {
        LOGW("installNativeLoadHook: libart.so not found via RTLD_NOLOAD, trying dlopen");
        libart = dlopen("libart.so", RTLD_NOW);
    }

    if (libart != nullptr) {
        // ART internal symbol for Runtime_nativeLoad (static JNI method)
        // Try multiple symbol names as it varies across Android versions
        const char* symbols[] = {
            "_ZN3artL18Runtime_nativeLoadEP7_JNIEnvP7_jclassP8_jstringP8_jobjectS5_",
            "Runtime_nativeLoad",
            nullptr
        };

        for (int i = 0; symbols[i] != nullptr; i++) {
            g_orig_nativeLoad_fn = dlsym(libart, symbols[i]);
            if (g_orig_nativeLoad_fn != nullptr) {
                LOGI("installNativeLoadHook: found original at symbol '%s'", symbols[i]);
                break;
            }
        }
    }

    // Newer ART builds do not expose Runtime.nativeLoad as a replaceable JNI
    // method. RegisterNatives then throws NoSuchMethodError. Confirm the exact
    // method first, and never register when it is absent.
    const char* native_load_sig =
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;";
    jmethodID nativeLoadMethod = env->GetStaticMethodID(
        runtimeClass, "nativeLoad", native_load_sig);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (nativeLoadMethod == nullptr || g_orig_nativeLoad_fn == nullptr) {
        LOGW("installNativeLoadHook: Runtime.nativeLoad is not hookable on this device");
        g_orig_nativeLoad_fn = nullptr;
        env->DeleteLocalRef(runtimeClass);
        return false;
    }

    JNINativeMethod methods[] = {
        {
            "nativeLoad",
            native_load_sig,
            reinterpret_cast<void*>(hooked_nativeLoad)
        }
    };

    jint result = env->RegisterNatives(runtimeClass, methods, 1);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = JNI_ERR;
    }
    env->DeleteLocalRef(runtimeClass);

    if (result != JNI_OK) {
        LOGW("installNativeLoadHook: RegisterNatives rejected Runtime.nativeLoad");
        g_orig_nativeLoad_fn = nullptr;
        return false;
    }

    LOGI("installNativeLoadHook: SUCCESS — Runtime.nativeLoad hooked via RegisterNatives");
    return true;
}

/**
 * JNI bridge for NativeHookBridge.nativeInstallRuntimeLoadHook()
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeInstallRuntimeLoadHook(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    return installNativeLoadHook(env) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
