/*
 * Minimal Zygisk API declarations derived from topjohnwu/zygisk-module-sample.
 * Upstream license permits use, copy, modification, and distribution.
 */
#pragma once

#include <jni.h>
#include <sys/types.h>
#include <cstdint>

#define ZYGISK_API_VERSION 4

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual void onLoad([[maybe_unused]] Api *api, [[maybe_unused]] JNIEnv *env) {}
    virtual void preAppSpecialize([[maybe_unused]] AppSpecializeArgs *args) {}
    virtual void postAppSpecialize([[maybe_unused]] const AppSpecializeArgs *args) {}
    virtual void preServerSpecialize([[maybe_unused]] ServerSpecializeArgs *args) {}
    virtual void postServerSpecialize([[maybe_unused]] const ServerSpecializeArgs *args) {}
};

struct AppSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jobjectArray &rlimits;
    jint &mount_external;
    jstring &se_info;
    jstring &nice_name;
    jstring &instruction_set;
    jstring &app_data_dir;
    jintArray *const fds_to_ignore;
    jboolean *const is_child_zygote;
    jboolean *const is_top_app;
    jobjectArray *const pkg_data_info_list;
    jobjectArray *const whitelisted_data_info_list;
    jboolean *const mount_data_dirs;
    jboolean *const mount_storage_dirs;
    AppSpecializeArgs() = delete;
};

struct ServerSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jlong &permitted_capabilities;
    jlong &effective_capabilities;
    ServerSpecializeArgs() = delete;
};

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

namespace internal {
struct api_table;
template <class T>
void entry_impl(api_table *table, JNIEnv *env);
} // namespace internal

struct Api {
    void hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods);
    int connectCompanion();
    void setOption(Option opt);

private:
    internal::api_table *tbl = nullptr;
    template <class T>
    friend void internal::entry_impl(internal::api_table *, JNIEnv *);
};

namespace internal {

struct module_abi {
    long api_version;
    ModuleBase *impl;
    void (*preAppSpecialize)(ModuleBase *, AppSpecializeArgs *);
    void (*postAppSpecialize)(ModuleBase *, const AppSpecializeArgs *);
    void (*preServerSpecialize)(ModuleBase *, ServerSpecializeArgs *);
    void (*postServerSpecialize)(ModuleBase *, const ServerSpecializeArgs *);

    explicit module_abi(ModuleBase *module) : api_version(ZYGISK_API_VERSION), impl(module) {
        preAppSpecialize = [](ModuleBase *m, AppSpecializeArgs *args) { m->preAppSpecialize(args); };
        postAppSpecialize = [](ModuleBase *m, const AppSpecializeArgs *args) { m->postAppSpecialize(args); };
        preServerSpecialize = [](ModuleBase *m, ServerSpecializeArgs *args) { m->preServerSpecialize(args); };
        postServerSpecialize = [](ModuleBase *m, const ServerSpecializeArgs *args) { m->postServerSpecialize(args); };
    }
};

struct api_table {
    void *impl;
    bool (*registerModule)(api_table *, module_abi *);
    void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
    void (*pltHookRegister)(dev_t, ino_t, const char *, void *, void **);
    bool (*exemptFd)(int);
    bool (*pltHookCommit)();
    int (*connectCompanion)(void *);
    void (*setOption)(void *, Option);
    int (*getModuleDir)(void *);
    uint32_t (*getFlags)(void *);
};

template <class T>
void entry_impl(api_table *table, JNIEnv *env) {
    static Api api;
    api.tbl = table;
    static T module;
    static module_abi abi(&module);
    if (!table->registerModule(table, &abi)) return;
    module.onLoad(&api, env);
}
} // namespace internal

inline void Api::hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods) {
    if (tbl->hookJniNativeMethods) tbl->hookJniNativeMethods(env, className, methods, numMethods);
}

inline int Api::connectCompanion() {
    return tbl->connectCompanion ? tbl->connectCompanion(tbl->impl) : -1;
}

inline void Api::setOption(Option opt) {
    if (tbl->setOption) tbl->setOption(tbl->impl, opt);
}

#define REGISTER_ZYGISK_MODULE(clazz) \
extern "C" [[gnu::visibility("default")]] void zygisk_module_entry(zygisk::internal::api_table *table, JNIEnv *env) { \
    zygisk::internal::entry_impl<clazz>(table, env); \
}

#define REGISTER_ZYGISK_COMPANION(func) \
extern "C" [[gnu::visibility("default")]] void zygisk_companion_entry(int fd) { \
    func(fd); \
}

} // namespace zygisk
