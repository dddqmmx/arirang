#include "sensor_vtable_patch.hpp"

#include "logging.hpp"
#include "sensor_aosp_types.hpp"
#include "sensor_rules.hpp"
#include "hook/inline_hook.hpp"

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <new>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace arirang {

using namespace arirang::sensor;

namespace {

// ---------------------------------------------------------------------------
// Vtable slot patching.
//
// BnSensorServer::onTransact calls getSensorList via vtable[4] (offset 0x20)
// and getDynamicSensorList via vtable[5] (offset 0x28).
// We hook onTransact to capture the vtable pointer and patch these slots.
// ---------------------------------------------------------------------------

constexpr size_t kGetSensorListSlot = 0x20;
constexpr size_t kGetDynamicSensorListSlot = 0x28;

// Forward declarations for hooks used in patch_vtable.
Vector hook_get_sensor_list(void *self, const void *opPackageName);
Vector hook_get_dynamic_sensor_list(void *self, const void *opPackageName);

using GetSensorListFn = Vector (*)(void *, const void *);
using OnTransactFn = int32_t (*)(void *, uint32_t, const void *, void *, uint32_t);

std::atomic<GetSensorListFn> s_orig_get_sensor_list{nullptr};
std::atomic<GetSensorListFn> s_orig_get_dynamic_sensor_list{nullptr};
void *s_orig_on_transact = nullptr;
std::mutex s_vtable_mutex;
std::atomic<bool> s_vtable_patched{false};

// system_server routinely exceeds 5k maps on modern Android (JIT, ashmem,
// binder, graphics). The previous 4k cap truncated the tail of /proc/self/maps
// and caused patch_vtable() to reject live SensorService heap objects as
// "not in readable memory".
constexpr size_t kMaxMemoryMappings = 65536;

struct MemoryMapping {
    uintptr_t start = 0;
    uintptr_t end = 0;
    int protection = 0;
    bool private_mapping = false;
    char path[512] = {};
};

struct ProtectedPage {
    uintptr_t start = 0;
    int original_protection = 0;
};

const char *path_basename(const char *path) {
    if (path == nullptr) return "";
    const char *slash = std::strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

bool is_trusted_sensor_library(const char *path) {
    if (path == nullptr || path[0] != '/' || std::strstr(path, " (deleted)") != nullptr ||
        std::strstr(path, "/memfd:") != nullptr) {
        return false;
    }
    const bool trusted_root = std::strncmp(path, "/system/lib64/", 14) == 0 ||
        std::strncmp(path, "/system_ext/lib64/", 18) == 0 ||
        std::strncmp(path, "/product/lib64/", 15) == 0 ||
        std::strncmp(path, "/vendor/lib64/", 14) == 0 ||
        (std::strncmp(path, "/apex/", 6) == 0 && std::strstr(path, "/lib64/") != nullptr);
    if (!trusted_root) return false;
    const char *base = path_basename(path);
    return std::strcmp(base, "libsensor.so") == 0 ||
           std::strcmp(base, "libsensorservice.so") == 0;
}

bool parse_memory_mapping(const char *line, MemoryMapping *out) {
    unsigned long long start = 0;
    unsigned long long end = 0;
    unsigned long long offset = 0;
    unsigned long long inode = 0;
    char permissions[8] = {};
    char device[32] = {};
    char path[512] = {};
    const int fields = std::sscanf(
        line, "%llx-%llx %7s %llx %31s %llu %511[^\n]",
        &start, &end, permissions, &offset, device, &inode, path);
    if (fields < 3 || start >= end) return false;

    out->start = static_cast<uintptr_t>(start);
    out->end = static_cast<uintptr_t>(end);
    out->protection = (permissions[0] == 'r' ? PROT_READ : 0) |
                      (permissions[1] == 'w' ? PROT_WRITE : 0) |
                      (permissions[2] == 'x' ? PROT_EXEC : 0);
    out->private_mapping = permissions[3] == 'p';
    if (fields >= 7) {
        const char *trimmed = path;
        while (*trimmed == ' ' || *trimmed == '\t') ++trimmed;
        std::snprintf(out->path, sizeof(out->path), "%s", trimmed);
    }
    return true;
}

std::vector<MemoryMapping> read_memory_mappings() {
    std::vector<MemoryMapping> mappings;
    mappings.reserve(256);
    const int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return mappings;
    FILE *file = fdopen(fd, "r");
    if (file == nullptr) {
        close(fd);
        return mappings;
    }

    char line[1024];
    while (mappings.size() < kMaxMemoryMappings &&
           std::fgets(line, sizeof(line), file) != nullptr) {
        MemoryMapping mapping;
        if (parse_memory_mapping(line, &mapping)) {
            mappings.push_back(mapping);
        }
    }
    std::fclose(file);
    return mappings;
}

// AArch64 Android enables top-byte-ignore (TBI / MTE tags). Binder objects
// handed to onTransact may carry a non-zero tag in bits 56-63; those bits must
// be cleared before comparing against /proc/self/maps ranges.
uintptr_t untag_user_pointer(uintptr_t address) {
    return address & ((static_cast<uintptr_t>(1) << 56) - 1);
}

const MemoryMapping *mapping_for(uintptr_t address,
                                 size_t size,
                                 const std::vector<MemoryMapping> &mappings) {
    address = untag_user_pointer(address);
    if (size == 0 || address > UINTPTR_MAX - size) return nullptr;
    for (const auto &mapping : mappings) {
        if (address >= mapping.start && address + size <= mapping.end) {
            return &mapping;
        }
    }
    return nullptr;
}

bool validate_function_target(void *function,
                              const char *expected_name,
                              const std::vector<MemoryMapping> &mappings,
                              Dl_info *info) {
    if (function == nullptr || dladdr(function, info) == 0 ||
        info->dli_fbase == nullptr || !is_trusted_sensor_library(info->dli_fname)) {
        return false;
    }
    if (info->dli_sname != nullptr) {
        if (std::strstr(info->dli_sname, expected_name) == nullptr) {
            return false;
        }
    } else {
        // Stripped release builds commonly have no symbol name for dladdr to
        // report. Fall back to the library-path + mapping-protection checks
        // below only; this is logged so a weaker validation path is never
        // silently taken during triage.
        log_warn(std::string("sensor_spoofer: no symbol name available for ") +
                 expected_name + ", falling back to library+mapping checks");
    }

    const auto *mapping = mapping_for(reinterpret_cast<uintptr_t>(function), 1, mappings);
    return mapping != nullptr && (mapping->protection & (PROT_READ | PROT_EXEC)) ==
               (PROT_READ | PROT_EXEC) &&
           (mapping->protection & PROT_WRITE) == 0 &&
           mapping->private_mapping && is_trusted_sensor_library(mapping->path) &&
           std::strcmp(path_basename(mapping->path), path_basename(info->dli_fname)) == 0;
}

bool add_vtable_page(uintptr_t slot,
                     long page_size,
                     const char *expected_library,
                     const std::vector<MemoryMapping> &mappings,
                     std::vector<ProtectedPage> *pages) {
    const auto *mapping = mapping_for(slot, sizeof(void *), mappings);
    if (mapping == nullptr || (mapping->protection & PROT_READ) == 0 ||
        (mapping->protection & (PROT_WRITE | PROT_EXEC)) != 0 ||
        !mapping->private_mapping || !is_trusted_sensor_library(mapping->path) ||
        std::strcmp(path_basename(mapping->path), expected_library) != 0) {
        return false;
    }

    const auto page_mask = static_cast<uintptr_t>(page_size - 1);
    const uintptr_t page = slot & ~page_mask;
    for (const auto &existing : *pages) {
        if (existing.start == page) return true;
    }
    pages->push_back({page, mapping->protection});
    return true;
}

bool restore_vtable_pages(const std::vector<ProtectedPage> &pages, size_t page_size) {
    bool restored = true;
    for (const auto &page : pages) {
        if (mprotect(reinterpret_cast<void *>(page.start), page_size,
                     page.original_protection) != 0) {
            restored = false;
        }
    }
    return restored;
}

bool make_vtable_pages_writable(const std::vector<ProtectedPage> &pages, size_t page_size) {
    size_t changed = 0;
    for (; changed < pages.size(); ++changed) {
        const auto &page = pages[changed];
        if (mprotect(reinterpret_cast<void *>(page.start), page_size,
                     page.original_protection | PROT_WRITE) == 0) {
            continue;
        }
        for (size_t rollback = 0; rollback < changed; ++rollback) {
            mprotect(reinterpret_cast<void *>(pages[rollback].start), page_size,
                     pages[rollback].original_protection);
        }
        return false;
    }
    return true;
}

bool restore_vtable_slots(void **first_slot,
                          void *first_hook,
                          void *first_original,
                          void **second_slot,
                          void *second_hook,
                          void *second_original) {
    void *expected_second = second_hook;
    const bool second_restored = __atomic_compare_exchange_n(
        second_slot, &expected_second, second_original, false,
        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    void *expected_first = first_hook;
    const bool first_restored = __atomic_compare_exchange_n(
        first_slot, &expected_first, first_original, false,
        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    return first_restored && second_restored;
}

void patch_vtable(void *sensor_service) {
    if (sensor_service == nullptr) return;
    std::lock_guard<std::mutex> lock(s_vtable_mutex);
    if (s_vtable_patched.load(std::memory_order_acquire)) return;

    std::vector<MemoryMapping> mappings;
    try {
        mappings = read_memory_mappings();
    } catch (const std::bad_alloc &) {
        log_warn("sensor_spoofer: failed to inspect process mappings");
        return;
    }
    // Strip AArch64 TBI/MTE top-byte tags before any maps comparison or load.
    // Do not require a maps hit for the service object itself: OEM system_server
    // heaps sometimes use annotations that make a pure range lookup flaky, and
    // the real safety gate is the dladdr + trusted-library check on the vtable
    // slot targets below (same as the pre-refactor sensor_spoofer).
    const uintptr_t self_addr =
        untag_user_pointer(reinterpret_cast<uintptr_t>(sensor_service));
    auto *self = reinterpret_cast<void *>(self_addr);
    void **vtable = *reinterpret_cast<void ***>(self);
    if (vtable == nullptr) {
        log_warn("sensor_spoofer: vtable pointer is null");
        return;
    }

    auto **list_slot = &vtable[kGetSensorListSlot / sizeof(void *)];
    auto **dynamic_slot = &vtable[kGetDynamicSensorListSlot / sizeof(void *)];
    const uintptr_t slot1 = reinterpret_cast<uintptr_t>(list_slot);
    const uintptr_t slot2 = reinterpret_cast<uintptr_t>(dynamic_slot);

    // The slot offsets are AOSP-derived for BnSensorServer. We capture them on
    // first real onTransact call because that gives us the actual service object
    // instance and its final vtable after construction.
    const auto original_list = reinterpret_cast<GetSensorListFn>(
        __atomic_load_n(list_slot, __ATOMIC_ACQUIRE));
    const auto original_dynamic = reinterpret_cast<GetSensorListFn>(
        __atomic_load_n(dynamic_slot, __ATOMIC_ACQUIRE));
    Dl_info list_info{};
    Dl_info dynamic_info{};
    if (!validate_function_target(reinterpret_cast<void *>(original_list),
                                  "getSensorList", mappings, &list_info) ||
        !validate_function_target(reinterpret_cast<void *>(original_dynamic),
                                  "getDynamicSensorList", mappings, &dynamic_info) ||
        list_info.dli_fbase != dynamic_info.dli_fbase) {
        log_warn("sensor_spoofer: refusing untrusted or mismatched vtable targets");
        return;
    }

    const long page_size_value = sysconf(_SC_PAGESIZE);
    if (page_size_value <= 0 ||
        (static_cast<unsigned long>(page_size_value) &
         (static_cast<unsigned long>(page_size_value) - 1)) != 0) {
        log_warn("sensor_spoofer: invalid page size");
        return;
    }
    const size_t page_size = static_cast<size_t>(page_size_value);
    std::vector<ProtectedPage> pages;
    try {
        pages.reserve(2);
        const char *library = path_basename(list_info.dli_fname);
        if (!add_vtable_page(slot1, page_size_value, library, mappings, &pages) ||
            !add_vtable_page(slot2, page_size_value, library, mappings, &pages)) {
            log_warn("sensor_spoofer: vtable slots are outside trusted read-only data");
            return;
        }
    } catch (const std::bad_alloc &) {
        log_warn("sensor_spoofer: failed to prepare vtable page state");
        return;
    }

    log_info("sensor_spoofer: validated SensorService vtable targets");

    if (!make_vtable_pages_writable(pages, page_size)) {
        log_warn("sensor_spoofer: failed to make vtable page writable");
        return;
    }

    s_orig_get_sensor_list.store(original_list, std::memory_order_release);
    s_orig_get_dynamic_sensor_list.store(original_dynamic, std::memory_order_release);
    void *expected_list = reinterpret_cast<void *>(original_list);
    const bool list_patched = __atomic_compare_exchange_n(
        list_slot, &expected_list, reinterpret_cast<void *>(hook_get_sensor_list), false,
        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    void *expected_dynamic = reinterpret_cast<void *>(original_dynamic);
    const bool dynamic_patched = list_patched && __atomic_compare_exchange_n(
        dynamic_slot, &expected_dynamic,
        reinterpret_cast<void *>(hook_get_dynamic_sensor_list), false,
        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    if (!dynamic_patched) {
        if (list_patched) {
            void *expected_hook = reinterpret_cast<void *>(hook_get_sensor_list);
            __atomic_compare_exchange_n(list_slot, &expected_hook,
                                        reinterpret_cast<void *>(original_list), false,
                                        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
        }
        restore_vtable_pages(pages, page_size);
        log_warn("sensor_spoofer: vtable changed during patch; rolled back");
        return;
    }

    if (!restore_vtable_pages(pages, page_size)) {
        const bool writable_again = make_vtable_pages_writable(pages, page_size);
        const bool slots_restored = writable_again && restore_vtable_slots(
            list_slot, reinterpret_cast<void *>(hook_get_sensor_list),
            reinterpret_cast<void *>(original_list), dynamic_slot,
            reinterpret_cast<void *>(hook_get_dynamic_sensor_list),
            reinterpret_cast<void *>(original_dynamic));
        const bool protection_restored = restore_vtable_pages(pages, page_size);
        if (!slots_restored || !protection_restored) {
            log_warn("sensor_spoofer: vtable rollback could not be completed safely");
        }
        log_warn("sensor_spoofer: failed to restore vtable protection; patch rolled back");
        return;
    }

    s_vtable_patched.store(true, std::memory_order_release);
    log_info("sensor_spoofer: vtable slots patched");
}

// ---------------------------------------------------------------------------
// Hook functions.
// ---------------------------------------------------------------------------

Vector hook_get_sensor_list(void *self, const void *opPackageName) {
    const auto original = s_orig_get_sensor_list.load(std::memory_order_acquire);
    if (original == nullptr) {
        // Unreachable by construction: patch_vtable() release-stores this
        // pointer before it CAS-publishes the vtable slot that makes this
        // hook reachable, and the load above is acquire-paired with that
        // release. Kept as defense-in-depth: a default Vector() has a null
        // mVtableDummy, which would crash BnSensorServer::onTransact's
        // caller rather than fail safely, so this is logged loudly if the
        // invariant is ever violated.
        log_warn("sensor_spoofer: hook_get_sensor_list called before original was published");
        return Vector();
    }
    Vector result = original(self, opPackageName);
    apply_rules(&result);
    return result;
}

Vector hook_get_dynamic_sensor_list(void *self, const void *opPackageName) {
    const auto original = s_orig_get_dynamic_sensor_list.load(std::memory_order_acquire);
    if (original == nullptr) {
        // See hook_get_sensor_list: unreachable by construction, logged as
        // defense-in-depth in case that invariant is ever violated.
        log_warn("sensor_spoofer: hook_get_dynamic_sensor_list called before original was published");
        return Vector();
    }
    Vector result = original(self, opPackageName);
    apply_rules(&result);
    return result;
}

int32_t hook_on_transact(void *self, uint32_t code, const void *data, void *reply, uint32_t flags) {
    if (!s_vtable_patched.load(std::memory_order_acquire)) {
        patch_vtable(self);
    }
    const auto original = reinterpret_cast<OnTransactFn>(
        __atomic_load_n(&s_orig_on_transact, __ATOMIC_ACQUIRE));
    return original != nullptr ? original(self, code, data, reply, flags) : -ENOSYS;
}

} // namespace

bool install_on_transact_hook(void *libsensor) {
    if (libsensor == nullptr) {
        log_warn("sensor_spoofer: libsensor handle null, skipping onTransact hook");
        return false;
    }

    void *on_transact = dlsym(libsensor, "_ZN7android14BnSensorServer10onTransactEjRKNS_6ParcelEPS1_j");
    if (on_transact == nullptr) {
        log_warn("sensor_spoofer: failed to resolve BnSensorServer::onTransact");
        return false;
    }

    log_info("sensor_spoofer: resolved BnSensorServer::onTransact");

    // onTransact is used only as a discovery hook for the live service object.
    // After the first transaction patches the vtable, regular getSensorList
    // calls go directly through the vtable hooks above.
    if (!inline_hook_branch(on_transact, reinterpret_cast<void *>(hook_on_transact),
                            &s_orig_on_transact)) {
        log_warn("sensor_spoofer: failed to inline-hook BnSensorServer::onTransact");
        return false;
    }

    log_info("sensor_spoofer: installed BnSensorServer::onTransact hook, waiting for vtable patch");
    return true;
}

} // namespace arirang
