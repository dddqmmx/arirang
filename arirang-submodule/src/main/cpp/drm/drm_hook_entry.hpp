// drm_hook_entry.hpp
// C-linkage accessors for the AIDL and HIDL hook entry points.
// The actual implementations (arirang_drm_hook.cpp) use complex C++ types
// (AIDL_ScopedAStatus, hidl::string, std::function). These accessors
// expose them as simple void* so that other translation units can obtain
// function addresses without importing the HIDL type definitions.

#pragma once

#ifdef __cplusplus
extern "C" {
#endif

void* arirang_drm_aidl_entry_get(void);
void* arirang_drm_hidl_hook_get(void);

#ifdef __cplusplus
}
#endif
