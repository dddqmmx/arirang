# Arirang Submodule DRM Hook — 深度解析

> 本文从“业务目标 → 系统调用链 → 启动注入 → 符号解析 → vtable/inline 双策略 → ABI 适配 → SELinux/安全边界 → 验证与失败模式”完整拆解 `arirang-submodule` 的 Widevine `deviceUniqueId` 伪造实现。
>
> 配套文档：
> - `drm_hook_research.md` — 选型过程与参考机验证记录
> - `hook_file_design.md` — `hook/` 底层 helper 的代码结构说明
>
> **架构约束（硬性）：** 仅 ARM64 (AArch64)。vtable 扫描、inline trampoline、ptrace injector 均按 64 位指针与 AArch64 指令编码实现；CMake 在非 `aarch64|arm64` 上直接 `FATAL_ERROR`。

---

## 0. 一句话总结

App 把伪造的 `widevineDrmId` 写进 config → 模块在 `post-fs-data` 把 `libarirang_drm_hook.so` 与 ID 文件 stage 到 `/dev/.arirang`，并 bind-mount 到一个**未被任何进程映射、也不被 linker 配置引用**的 vendor 库路径 → late boot 的 `service.sh` 用 `arirang_injector` 对 **Widevine DRM HAL 守护进程** 做 ptrace remote-`dlopen` → hook 库 constructor 起后台线程，在已加载的 `libwvhidl.so` / `libwvaidl.so` 上找到 `getPropertyByteArray`，**优先改 vtable 数据指针**，失败再 **inline 改入口指令** → 当 app 调 `MediaDrm.getPropertyByteArray(PROPERTY_DEVICE_UNIQUE_ID)` 时，HAL 返回伪造的 16 字节 ID。

**绝不注入第三方 App 进程。** 全部动作限定在系统层 DRM HAL / framework 边界内。

---

## 1. 为什么要 Hook，以及 Hook 在哪一层

### 1.1 业务目标

Android 上 Widevine `deviceUniqueId` 是许多应用/风控用于设备指纹的字节数组，Java 侧入口：

```kotlin
MediaDrm(widevineUuid).getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
// 内部字符串属性名 = "deviceUniqueId"
```

自检应用（`arirang-selfcheck`）正是通过上述路径验证 spoof 是否生效。

### 1.2 为什么不在 App 进程 Hook

| 方案 | 问题 |
|------|------|
| Xposed/Zygisk 进 App 拦截 `MediaDrm` | 违反项目硬约束；污染每个媒体相关 App；性能与兼容性差 |
| Hook `libmediandrm` / framework Java | 版本碎片化；部分路径可绕过 |
| Hook CDM 内部 `CryptoSession::GetInternalDeviceUniqueId` | 非虚函数、直接分支调用，只能指令级 hook，且符号更易被 strip |

**正确层：Widevine DRM HAL 守护进程**（系统服务，用户 `mediadrm`）。所有 App 的 `MediaDrm` 最终经 Binder/HIDL/AIDL 落到这里。在 HAL 内伪造一次，全系统生效，且不碰第三方进程。

### 1.3 参考机上的目标二进制

| 文件 | 典型路径 | 角色 |
|------|----------|------|
| HAL 守护进程 | `/vendor/bin/hw/android.hardware.drm@1.4-service.widevine` 等 | Binder 服务宿主 |
| HIDL 插件 | `/vendor/lib64/libwvhidl.so` | `WVDrmPlugin::getPropertyByteArray` |
| AIDL 插件 | `/vendor/lib64/libwvaidl.so` | AIDL 等价实现 |
| CDM 引擎 | `/vendor/lib64/mediadrm/libwvdrmengine.so` | 真正生成 device unique ID 的核心 |

参考验证机：Pixel 8 Pro (`husky`)，Android 16，KernelSU Next。在 `libwvhidl.so` 中：

- 导出 mangled 方法：`...WVDrmPlugin20getPropertyByteArray...`
- 导出 vtable：`_ZTVN5wvdrm...WVDrmPluginE`
- 运行时 `vtable + 0xf0` 槽位指向 `getPropertyByteArray`（绝对指针）

`getPropertyByteArray` **定义在 so 自身**，经 **C++ 虚调用** 分发，因此 GOT/PLT hook 够不到；vtable 数据改写才是主路径。

---

## 2. 产物与模块布局

CMake 产出三个 native 产物（均 arm64）：

| 产物 | 类型 | 打包位置 | 运行位置/方式 |
|------|------|----------|----------------|
| `libarirang_zygisk.so` | Zygisk 模块 | `zygisk/arm64-v8a.so` | 进 zygote/system_server 等（**与 DRM hook 无关**） |
| `libarirang_drm_hook.so` | 共享库 | `lib/libarirang_drm_hook.so` | stage 到 `/dev/.arirang/`，bind 到 vendor 路径，再被 HAL `dlopen` |
| `arirang_injector` | 可执行文件 | `bin/arirang_injector` | `service.sh` 以 root 对 HAL 做 ptrace inject |

Gradle `:arirang-submodule:packageModule` 打成 Magisk/KSU/APatch 可刷 zip。

**设计隔离：** `libarirang_drm_hook.so` 与 Zygisk 模块 **零业务耦合**，故意不进 App 进程。CMake 注释写明：DRM hook 库“Intentionally has NO overlap with the Zygisk module — it must never be loaded into app processes.”

`libarirang_drm_hook.so` 源文件：

```
drm/arirang_drm_hook.cpp   # constructor、spoof 读写、HIDL/AIDL shim
drm/drm_vtable_hook.cpp    # 主策略：轮询库 + 调 vtable_hook
drm/drm_inline_hook.cpp    # 降级：inline_hook_branch
hook/vtable_hook.cpp       # 通用数据段 slot 扫描/改写
hook/inline_hook.cpp       # 通用 AArch64 指令级 hook
hook/symbol_resolver.cpp   # dl_iterate_phdr + dynsym 子串匹配
utils/logging.cpp
```

链接：`log`、`dl`、`binder_ndk`（AIDL `ScopedAStatus`）。

---

## 3. 配置与伪造 ID 数据流

### 3.1 Manager App → config.json

主 App（`asia.nana7mi.arirang`）把唯一标识配置写入 device-encrypted 存储：

```
/data/user_de/0/asia.nana7mi.arirang/files/arirang-submodule/config.json
```

（CE 路径 `/data/user/0/...` 为备用。）关键字段：

| 字段 | 含义 |
|------|------|
| `enabled` | 总开关 |
| `uniqueIdentifierEnabled` | 唯一标识伪造开关 |
| `widevineDrmId` | 十六进制字符串（偶数长度，典型 32 hex = 16 字节） |

`SubmoduleConfigFiles.kt` 负责序列化；UI 侧 `UniqueIdentifierConfigScreen` 可随机生成。

### 3.2 post-fs-data 阶段提取（`module/lib/widevine.sh`）

在 zygote 起来之前：

1. 删除旧 `$LANDING_ID`（`/dev/.arirang/widevine_id`），避免陈旧 ID 残留。
2. 用 injector 的 `config` 子命令安全读取 JSON 顶层键（不引入 shell JSON 解析脆弱性）。
3. 仅当 `enabled=true` 且 `uniqueIdentifierEnabled=true` 且 hex 合法时写入。
4. 文件属性：`0:mediadrm`、`0640`、SELinux `u:object_r:arirang_data_file:s0`。

### 3.3 hook 库内读取（`arirang_drm_hook.cpp`）

路径优先级：

1. `/data/adb/modules/arirang-submodule/runtime/widevine_id`（模块私有 runtime，可选）
2. `/dev/.arirang/widevine_id`（post-fs-data 主 stage）

安全读取要点：

- `O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK`（禁 symlink 跟随）
- `fstat`：必须是普通文件、`uid==0`、`nlink==1`、**无 group/other 写**、大小 ≤ 1024
- 读前后两次 `fstat` 快照比对（dev/ino/size/mtime/ctime），防 TOCTOU 替换
- hex 解码为 `vector<uint8_t>`，经 `shared_ptr` + `atomic_load/store` 发布给 hook 回调（无锁快照）

属性名常量：`"deviceUniqueId"`（14 字节），与 `MediaDrm.PROPERTY_DEVICE_UNIQUE_ID` 一致。

---

## 4. 启动链路：如何把 .so 送进 HAL

整条链路分两个 Magisk/KSU 阶段脚本 + 一个 native injector。  
仓库内还有 `module/vendor/etc/init/zzz-arirang-drm.rc`（init `override` + `setenv LD_PRELOAD`），但 **当前 `stageModule` 任务并不打包该 rc**；线上主路径是 **bind-mount + ptrace inject**。rc 保留为历史/备选设计，下文单独说明。

### 4.1 `post-fs-data.sh`（/data 就绪、zygote 之前）

顺序：

```
arirang_common_init
  → arirang_staging_setup      # phase 1
  → arirang_vendor_bind        # phase 2
  → arirang_widevine_extract   # phase 3
  → arirang_resetprop_apply    # phase 4（系统属性伪造，与 DRM 无直接关系）
```

#### Phase 1 — Staging（`staging.sh`）

```
LANDING_DIR  = /dev/.arirang          # tmpfs 上的私有着陆点
LANDING_HOOK = .../libarirang_drm_hook.so
LANDING_ID   = .../widevine_id
LANDING_BINDPATH = .../bind_path      # 记录选中的 vendor 覆盖路径
```

动作：

1. 创建目录：`0:mediadrm`、`0750`、`arirang_data_file`
2. 清理上次 bind 记录与可能的 orphan mount（只 umount 带 `arirang_hook_file` 标签且 inode 与 hook 一致的挂载）
3. 从模块 `lib/libarirang_drm_hook.so` 复制到 landing：`0:mediadrm`、`0640`、`arirang_hook_file`

**为何用 `/dev/.arirang`：** 早期 boot 即可写的 tmpfs；与 App 数据隔离；配合 SELinux 类型把“可执行 hook 代码”和“含 ID 的数据”拆开。

#### Phase 2 — Vendor bind-mount（`vendor_bind.sh`）

问题：HAL 进程在 **vendor linker namespace** 里，不能随意 `dlopen` `/data/adb/...` 下的路径；需要一个 **vendor 可见路径** 上的文件名，再让 injector 对该路径 `dlopen`。

策略：**选一个永远不会被真实使用的 vendor `.so`，把 hook bind 上去。**

候选白名单（`common.sh` 的 `ARIRANG_VENDOR_CANDIDATES`）例如：

```
/vendor/lib64/android.hidl.token@1.0-utils.so
/vendor/lib64/android.hidl.token@1.0.so
/vendor/lib64/android.frameworks.cameraservice.common-V1-ndk.so
...
```

选择条件（全部满足才用）：

1. 在白名单内，且是 root 拥有的普通文件  
2. **当前不是 mountpoint**  
3. **没有任何进程的 `/proc/*/maps` 精确映射该路径**（精确字段匹配，避免 substring 误伤）  
4. **linker 配置**（`ld.config*.txt`）中没有该 basename 的精确 token  

选中后：

```sh
mount --bind "$LANDING_HOOK" "$bind_target"
# 校验 mountpoint + same_file(LANDING_HOOK, bind_target)
# 把路径写入 LANDING_BINDPATH（0600, root, arirang_data_file）
```

此后，`dlopen(bind_target)` 加载的是 hook 库内容，但路径看起来是“普通 vendor so”，能过 vendor namespace 的路径策略。

#### Phase 3 — Widevine ID stage

见 §3.2。

### 4.2 `service.sh`（late_start service）

Magisk/KSU 可能杀掉长时间跑的 service 脚本，因此：

1. **前台**只做：校验 injector、staging、bind 记录；抢 lock；`fork` 后台 worker 后立刻 `exit 0`
2. **Worker** 最多等 300s 找到唯一合法 HAL，再执行 inject

#### HAL 身份校验（防误注入）

对每个 `pidof` 候选：

| 检查 | 要求 |
|------|------|
| uid | `[1000, 10000)`（系统服务区间，非 App） |
| exe | `/vendor/bin/*`、`/system/bin/*`、`/apex/*/bin/*`，非 deleted |
| 名称 | 匹配预期 HAL 名（含 `widevine` 或 `clearkey`） |
| ClearKey 特例 | 必须 maps 里已加载支持的 Widevine so |
| 已注入 | maps 中 **没有** bind 目标路径（已注入则跳过） |
| 组 | 进程 gids 包含 hook 文件的 group（mediadrm） |
| starttime | 读前后一致，防 PID 复用 |

候选进程名顺序：

1. `android.hardware.drm@1.4-service.widevine`
2. `android.hardware.drm@1.3-service.widevine`
3. `vendor.drm-widevine-hal-1-4`
4. `android.hardware.drm@1.4-service.clearkey`（仅当已加载 WV 库）

必须 **恰好 1 个** 通过校验的 pid；多个则继续等。

#### 调用 injector

```sh
arirang_injector <hal_pid> <bind_target> <starttime>
```

`starttime` 在 attach 后再校验一次，关闭 attach 窗口期的 PID 复用漏洞。

### 4.3 `arirang_injector` — ptrace remote dlopen

目标：在**已运行**的 HAL 里加载 hook `.so`，不依赖启动时 `LD_PRELOAD`。

#### 算法

```
1. PTRACE_ATTACH(pid) → wait STOP
2. （可选）校验 /proc/pid/stat starttime
3. 保存全部寄存器
4. 在目标 maps 中找足够大的 rw 区域（优先 [anon:libc_malloc]）
5. 在该区域末尾附近写入 so 绝对路径字符串（先读出原字节以便恢复）
6. 解析目标进程中的 dlopen：
     本地 dlsym(RTLD_DEFAULT,"dlopen") → dladdr 得本地 base/offset
     在目标 maps 找同 linker 映射 → remote = remote_base + offset
7. 设置寄存器（AArch64 ABI）：
     x0 = path, x1 = RTLD_NOW, x2 = 0
     x30(LR) = 0, pc = dlopen, sp 16 字节对齐
8. PTRACE_CONT → dlopen 返回后因 LR=0 触发 SIGSEGV 而 STOP
9. 读 x0 = handle（NULL 则失败）
10. PtraceGuard 析构：恢复 scratch 内存 + 寄存器 + DETACH
```

**不用断点指令、不改目标代码页**；用 LR=0 制造可靠的返回停点。

**PtraceGuard：** 任意错误/异常路径都会恢复状态再 detach，避免系统关键 HAL 卡在半调用状态。

#### 旁路功能

```
arirang_injector config <path> <key>
```

解析扁平 JSON 顶层键，供 shell 安全读配置（`service`/`post-fs-data` 间接依赖）。

### 4.4 备选：init.rc LD_PRELOAD（当前未进 zip）

`zzz-arirang-drm.rc` 用 `override` 覆盖多种 Widevine service 定义，追加：

```
setenv LD_PRELOAD /system/lib64/libarirang_drm_hook.so
```

设计意图：在 HAL **启动时**自动加载。实际打包路径以 bind+inject 为准，因为：

- 不依赖把 so 放到 `/system/lib64` 的 overlay
- 不依赖 init 服务定义与 OEM 完全一致（override 写错会导致 HAL 起不来）
- HAL 若已启动，rc 无法补救，inject 可以

---

## 5. Hook 库进程内生命周期

### 5.1 Constructor

```cpp
__attribute__((constructor)) void arirang_drm_hook_init() {
    pthread_create(&tid, nullptr, worker, nullptr);
    pthread_detach(tid);
}
```

`dlopen` 完成后 linker 调 constructor → 立刻起 **detached worker**，避免在 loader 锁下做重活。

### 5.2 Worker 双阶段

```
reload_spoof_bytes()
Phase 1: drm_vtable::poll_libraries()   // 最多 120 次 × 0.5s ≈ 60s
  成功 → return
Phase 2: 警告降级 → 对候选库 drm_inline::install_hook_in_library
  最多 30 次 × 0.5s
全失败 → log warn giving up
```

候选库列表（vtable 与 inline 共用思路）：

```
/vendor/lib64/mediadrm/libwvdrmengine.so
/vendor/lib64/libwvhidl.so
/vendor/lib64/libwvaidl.so
/system/lib64/mediadrm/libwvdrmengine.so
```

探测方式：`stat` 存在 + `dlopen(path, RTLD_NOW|RTLD_NOLOAD)` 证明**已映射进本进程**（不加载新库）。

### 5.3 原始函数指针发布

全局：

```cpp
void *g_trampoline;       // AIDL original
void *g_hidl_trampoline;  // HIDL original
const char *g_hook_method; // "vtable" | "inline_branch"
```

通过 C ABI 访问器：

- `arirang_drm_publish_original(hidl, ptr)`
- `arirang_drm_original(hidl)`
- `arirang_drm_publish_hook_method(method)`

**发布顺序（关键）：** 先 `publish_original(sym.address)`，再改 vtable slot。否则另一线程可能先看到新 slot、再读到空 original，导致空指针调用。

---

## 6. 符号解析（`symbol_resolver.cpp`）

### 6.1 不做什么

- **不** `dlopen` 新 so（避免改变 HAL 加载图）
- 只 `dl_iterate_phdr` 遍历**已映射**对象

### 6.2 流程

```
resolve_symbols_by_substring(library_path, "getPropertyByteArray")
  → phdr_callback：path 精确或 basename 匹配
  → 找 PT_DYNAMIC
  → DT_SYMTAB + DT_STRTAB
  → 符号数量：优先 DT_GNU_HASH chain 扫描，否则 DT_HASH.nchain
  → 过滤 STT_FUNC / STT_GNU_IFUNC，name 含 substring
  → address = load_base + st_value
```

`rebase_dynamic_pointer`：兼容 Android linker 把 `DT_*` 写成绝对地址或相对 load_base 两种形态。

GNU hash 遍历有 `kMaxSymbolsScanned` 上限，且 chain 地址必须落在某个 `PT_LOAD` 内，防损坏 ELF 导致越界长循环。

### 6.3 ABI 分类（`drm_symbol_classifier.hpp`）

同一子串可能命中多个 mangled 名。分类规则：

| 条件 | ABI |
|------|-----|
| name 含 `getPropertyByteArray` + `11hidl_string` + `8function` + `8hidl_vec`，且库 basename 为 `libwvhidl.so` | **HIDL** |
| name 含 `getPropertyByteArray` + `basic_string` + `vector`，且库 basename 为 `libwvaidl.so` | **AIDL** |
| 同时像 HIDL 又像 AIDL | **Unsupported**（拒绝，防装错 hook 形状） |
| 其它 | Unsupported |

HIDL 与 AIDL 的 C++ 调用约定完全不同，装错会直接崩 HAL。

---

## 7. 主策略：Vtable / Method-table Hook

### 7.1 为什么是“数据级”hook

C++ 虚调用：

```
obj → vptr → vtable[i] → 函数地址
```

vtable 在 `.data.rel.ro` / `.data`：**数据，不是代码**。改写 slot：

- 不碰指令序言 → 免疫 PAC/`paciasp` 破坏
- 不需要 ±128MB 近跳 trampoline
- 不依赖编译器生成的前 16 字节是否“可搬迁”
- 原函数指针直接存在 slot 里，**天然就是 trampoline**

### 7.2 `drm_vtable_hook.cpp` 安装流程

```
for each resolved symbol:
  classify → AIDL hook 或 HIDL hook 入口
  publish_original(sym.address)
  vtable_hook_install(lib, "getPropertyByteArray", hook, &patches)
  校验 patches[0].original_function == sym.address
  否则 uninstall + 试下一个符号
  成功：publish_hook_method("vtable")，打 logcat tag ArirangDrmHook
```

### 7.3 `vtable_hook_install` 内核（`vtable_hook.cpp`）

```
1. resolve_symbols_by_substring（再次解析，引擎自洽）
2. read /proc/self/maps
3. 对每个符号：
   a. 扫描该库所有 “可读、不可执行、private” 映射
   b. 绝对 8 字节 slot：值 == 函数地址 且 邻居像 vtable
   c. 若无绝对命中且 try_relative：再扫 rel32
4. 至少一个 slot 改写成功 → true
```

#### 扫描过滤：`is_library_data_map`

- 路径匹配目标库  
- `readable && !executable && private`  
- **不要求 writable**（`.data.rel.ro` 重定位后常只读）

#### 绝对指针 slot（参考机主路径）

```
for addr in [aligned_start, end) step 8:
  if *addr != symbol.address: continue
  if !looks_like_absolute_vtable_slot(addr): skip  # 邻居必须也指向可执行段
  mprotect 临时加写
  atomic CAS 写入 hook
  恢复原 protection
  记录 VtablePatch
```

`looks_like_absolute_vtable_slot`：检查 `addr±8` 的邻居值是否指向 executable 映射。  
**拒绝孤立全局函数指针**（值碰巧等于目标函数但不在 vtable 数组中）。

#### 相对 vtable（Itanium relative vtable，fallback）

```
destination = slot_addr + (int32)*slot
```

额外约束：

- 仅当该符号 **没有** 任何绝对 slot 被 patch 时才扫  
- `looks_like_relative_vtable_slot`：邻居 rel32 也要解析到 executable（挡 `.eh_frame` 误命中）  
- hook 与 slot 距离必须落在 int32 范围  

研究阶段发现：盲扫 rel32 会在 `.eh_frame` 出假阳性；邻居启发 + “绝对优先”是刻意设计。

#### 并发与回滚

- 全局 `g_patch_mutex` 串行化 install/uninstall  
- CAS 失败则放弃该 slot  
- protection 恢复失败 → CAS 写回原值再恢复（尽力原子回滚）  
- uninstall 时若 slot 已不是我们写的 replacement → **拒绝覆盖**（防踩后来 hook）

### 7.4 与 “硬编码 vtable 偏移” 的对比

| 硬编码 `_ZTV...` + offset | 当前实现 |
|---------------------------|----------|
| 依赖导出 vtable 符号名与布局 | 不依赖 offset；用函数地址反查 slot |
| OEM 一改布局就挂 | 只要 dynsym 仍有方法名即可 |
| 无法处理 strip 掉 vtable 符号的情况 | 仍可盲扫数据段中的指针 |

全 strip 掉 dynsym 时当前方案也会失败——研究文档把“更多 discovery backend”列为后续扩展点。

---

## 8. 降级策略：Inline Hook

当 vtable 60s 内未成功：

```
log WARN DOWNGRADE
for candidates:
  drm_inline::install_hook_in_library
```

### 8.1 实际使用的 API

`drm_inline_hook.cpp` 调用的是：

```cpp
arirang::inline_hook_branch(target, hook, trampoline_slot)
```

不是 16 字节 absolute jump 的 legacy 路径。成功后：

- original 从 `*trampoline_slot` 读出再 `publish_original`
- method 标记为 `"inline_branch"`

### 8.2 `inline_hook_branch` 机制（`inline_hook.cpp`）

AArch64 单条 `B imm26` 只能跳 **±128MB**，handler 通常在别的 so 里，距离不够。

因此：

```
1. 跳过入口 BTI（若有）
2. 若下一条是 PACIASP/PACIBSP，记录并跳过，patch 点后移
3. 在目标 ±120MB 内按页 MAP_FIXED_NOREPLACE 申请近邻页作 relay
4. relay 内容：
   - 若保留了 PAC：先 AUTIASP/AUTIBSP，再 LDR/BR 到 handler
   - 否则直接 LDR x16 / BR x16 到 handler
   - 另建 original replay：复制被覆盖的 BTI/PAC + 原指令，再 B 回 target+offset
5. 在 patch 点 CAS 写入单条 B → relay
6. clear_cache，恢复 RX
```

特点：

- **单条 32-bit 原子写**发布，无“半指令”撕裂  
- 保留 PAC 登录序列，relay 侧做对应 authenticate  
- 入口若是 PC-relative 指令且需要 replay → 直接拒绝  
- **无 uninstall**（进程生命周期内永久；与 vtable 不对称是刻意的）

### 8.3 为何 vtable 优先

| | Vtable | Inline |
|--|--------|--------|
| 改什么 | 数据指针 | 可执行代码 |
| PAC | 无关 | 需特殊处理，仍脆弱 |
| 序言敏感 | 否 | 是 |
| 附近内存 | 不需要 | 需要 ±128MB 空页 |
| 多线程安全窗口 | CAS 数据 | 仍有“已进入序言”竞态 |
| 可卸载 | 是 | 否 |

研究文档明确拒绝 “硬编码识别 paciasp 指令继续 inline” 作为主方案。

---

## 9. HIDL / AIDL Shim 如何改返回值

### 9.1 HIDL：`arirang_drm_hidl_hook`

签名近似：

```cpp
HidlReturnVoid hook(void *this,
                    const hidl::string &name,
                    std::function<void(Status, const hidl::vec<uint8_t>&)> cb);
```

本地模拟的 `hidl::string` / `hidl::vec` 布局经 `static_assert` 钉死（16 字节，align 8），必须与 bionic/libhidl 一致。

逻辑：

1. 若 `name` 不是 `"deviceUniqueId"` → 直接调 original  
2. 否则包一层 callback：original 成功且 spoof 非空时，用 **spoof 缓冲** 构造 `hidl::vec`（`mOwnsBuffer=false`，不释放我们的静态快照）再回调  
3. spoof 不可用则透传原始 vec  

**不替换函数返回值中的业务状态**，只替换 callback 里的字节数组——与 HIDL 异步回调风格一致。

### 9.2 AIDL：`arirang_drm_aidl_entry`

```cpp
ndk::ScopedAStatus entry(void *this,
                         const std::string &name,
                         std::vector<uint8_t> *output);
```

逻辑：

1. 调 original  
2. 若 `isOk()` 且 name 匹配 → `output->assign(spoof_bytes)`  

同步 out-parameter 模型，比 HIDL 简单。

### 9.3 日志节流

`log_hook_path_once` / `log_spoof_once`：每个 ABI 各打一次成功路径，避免刷屏。  
tag：`ArirangZygisk`（logging helper 历史 tag）与 `ArirangDrmHook`（vtable 成功专用）。

---

## 10. SELinux 与权限边界

`sepolicy.rule` 核心：

```
type arirang_hook_file   # 可执行 hook 代码
type arirang_data_file   # ID / lock / bind 记录（不可 execute）
```

对 DRM 域（`hal_drm_widevine` / `vendor_hal_drm_widevine` / `hal_drm_default` / `mediadrmserver`）：

| 类型 | 权限 |
|------|------|
| `arirang_data_file` | dir search；file read/open/getattr（**无 execute/map**） |
| `arirang_hook_file` | read/open/getattr/**execute/map** |
| `vendor_file` / `tmpfs` | 覆盖 bind 后的路径访问 |

ptrace：

```
allow su|init|ksu { drm domains } process ptrace
```

原则：**代码与敏感数据分类型**；HAL 能 map hook so，但不能执行 ID 文件。

Staging 侧所有路径都做：

- root uid、固定 mode、期望 SELinux context  
- bind 目标白名单 + 未映射 + 未引用  
- inject 前多重 identity 校验  

---

## 11. 端到端时序图

```
[Manager App]
  write config.json (widevineDrmId, flags)
        │
        ▼  reboot / 刷入模块后
[post-fs-data.sh]  (root, early boot)
  stage hook.so → /dev/.arirang/
  bind-mount → /vendor/lib64/<unused>.so
  stage widevine_id
        │
        ▼
[init 启动 Widevine HAL]  (user mediadrm)
        │
        ▼  late_start
[service.sh foreground]
  verify bind_path + hook
  fork worker → exit
        │
        ▼
[service.sh worker]
  wait unique HAL pid (≤300s)
  arirang_injector pid bind_path starttime
        │
        ▼
[arirang_injector]
  ptrace → remote dlopen(bind_path)
        │
        ▼
[libarirang_drm_hook.so constructor]
  worker: load spoof bytes
  poll libwvhidl / libwvaidl ...
  resolve getPropertyByteArray
  patch vtable slot → arirang_drm_*_entry
        │
        ▼ 任意 App / selfcheck
[MediaDrm.getPropertyByteArray(DEVICE_UNIQUE_ID)]
  → mediaserver/framework
  → Binder → Widevine HAL
  → virtual getPropertyByteArray
  → arirang_drm_hidl_hook / arirang_drm_aidl_entry
  → 返回 spoof 字节
```

---

## 12. 日志与设备验证

### 12.1 关注的 logcat tag

| Tag | 来源 |
|-----|------|
| `arirang_post_fs_data` | staging / bind / widevine |
| `arirang_service` | HAL 发现 / inject |
| `ArirangDrmHook` | vtable/inline 安装结果 |
| `ArirangZygisk` / `arirang_drm_hook:` | spoof 加载与命中 |

成功样例（研究文档实测）：

```
I ArirangDrmHook: vtable hook success for HIDL _ZN5wvdrm...getPropertyByteArray...
I ... arirang_drm_hook: spoof bytes loaded len=16
I ... arirang_drm_hook: spoofed deviceUniqueId byte[] (HIDL)
```

### 12.2 推荐验证步骤

1. `./gradlew :arirang-submodule:installModuleAndReboot`（或 KSU Next 变体）  
2. 主 App 打开唯一标识，填入已知 hex，保存  
3. 再 reboot 或至少确保 post-fs-data 重跑  
4. 打开 `arirang-selfcheck`，触发 `MediaDrm` device unique id 读取  
5. 对比返回 hex 与配置一致；logcat 有 spoof 日志  
6. 失败时抓：`logcat`、`/proc/<drm-pid>/maps`、`ls -lZ /dev/.arirang`、bind 目标是否仍是 mountpoint  

### 12.3 常见失败模式

| 现象 | 可能原因 |
|------|----------|
| bind 失败 | 白名单候选全被占用/映射/写进 ld.config |
| inject exit 6 | attach 后 starttime 变化（PID 复用） |
| inject exit 4 | remote dlopen NULL（路径/SELinux/命名空间） |
| vtable 全失败后 inline DOWNGRADE | 无可用数据 slot；或符号被 strip |
| inline 也失败 | PAC + PC-relative 序言；无近邻页；函数太短 |
| spoof 未替换 | ID 文件校验失败；enabled 开关关；hex 非法；只 hook 到错误 ABI |
| HAL 崩溃 | ABI 分类错误装了错 hook；inline 破坏了序言 |

---

## 13. 安全模型与威胁面（实现视角）

本模块以 root 模块身份运行，自身的防护重点是 **fail-closed** 与 **最小作用域**：

1. **作用域：** 仅 DRM HAL，不进第三方 App  
2. **路径白名单：** vendor 覆盖目标不可扩展到任意路径  
3. **TOCTOU：** spoof 文件读写前后 stat 快照；bind 前二次检查 inode  
4. **注入目标：** uid/exe/名称/maps/group/starttime 多层过滤  
5. **误 patch：** vtable 邻居启发；relative 仅作 fallback；uninstall 不覆盖外来修改  
6. **SELinux：** hook 可执行类型与数据分离  
7. **编译加固：** hidden visibility、full RELRO、`-z now`、branch protection、FORTIFY 等（`arirang_harden_target`）

仍依赖：设备已 root、模块被信任安装、SELinux 规则被 root manager 注入成功。这不是“对抗恶意 root”的模型，而是“在已授权 root 下尽量不炸系统、不误伤进程”。

---

## 14. 代码地图（按阅读顺序）

| 顺序 | 路径 | 读什么 |
|------|------|--------|
| 1 | `module/post-fs-data.sh` + `lib/{staging,vendor_bind,widevine}.sh` | 落地与 bind |
| 2 | `module/service.sh` | HAL 发现与 inject 调度 |
| 3 | `src/main/cpp/drm/arirang_injector.cpp` | ptrace remote dlopen |
| 4 | `src/main/cpp/drm/arirang_drm_hook.cpp` | constructor、spoof、HIDL/AIDL shim |
| 5 | `src/main/cpp/drm/drm_vtable_hook.cpp` | 主策略 glue |
| 6 | `src/main/cpp/hook/symbol_resolver.cpp` | dynsym 子串解析 |
| 7 | `src/main/cpp/hook/vtable_hook.cpp` | 数据段扫描与 CAS patch |
| 8 | `src/main/cpp/drm/drm_inline_hook.cpp` + `hook/inline_hook.cpp` | 降级指令 hook |
| 9 | `src/main/cpp/drm/drm_symbol_classifier.hpp` | ABI 判定 |
| 10 | `module/sepolicy.rule` | SELinux |
| 11 | `doc/drm_hook_research.md` | 选型与实机证据 |
| 12 | `doc/hook_file_design.md` | helper 内部结构细节 |

---

## 15. 设计决策备忘

| 决策 | 选择 | 原因 |
|------|------|------|
| Hook 层 | Widevine HAL | 系统级一次生效；不碰 App |
| 主 hook 技术 | Vtable 数据改写 | 抗 PAC、抗序言差异 |
| 降级 | Atomic branch + nearby relay | 无 vtable 时仍可尝试 |
| 注入 | ptrace remote dlopen | 不依赖 init override 与 system 分区 so |
| 路径可见性 | bind 到闲置 vendor so | 适配 vendor linker namespace |
| 符号查找 | substring + 已映射 ELF | 避免硬编码完整 mangled 名与版本号 |
| ID 传递 | 文件 + 严格 stat | HAL 与 App 无共享内存；权限清晰 |
| 架构 | arm64 only | 指令/指针/ABI 全特化 |

---

## 16. 与研究文档的差异（实现现状）

| 研究文档早期表述 | 当前代码 |
|------------------|----------|
| 主路径可能 LD_PRELOAD | 打包主路径是 bind + injector；rc 未进 stage |
| inline 覆盖前 16 字节 absolute jump | DRM 降级用 `inline_hook_branch`（单条 B + 近邻 relay） |
| spoof 路径仅 `/dev/.arirang/widevine_id` | 另支持模块 `runtime/widevine_id` |
| “不再使用 inline_hook” 为终态目标 | 仍保留为显式 DOWNGRADE fallback |

以 **源码与 `stageModule` 实际打包内容** 为准。

---

## 17. 维护建议

1. 改 vtable 扫描条件前，先在真机 `/proc/<hal>/maps` + 数据段 dump 验证，避免扩大误 patch。  
2. 新增 HIDL/AIDL 版本时，扩展 `classify_get_property_byte_array` 与候选库列表，并 `static_assert` 检查 HIDL 布局。  
3. 换 OEM 时优先确认：HAL 进程名、是否 strip dynsym、vendor 候选是否仍“未使用”。  
4. 不要把 DRM hook 链进 Zygisk 模块或 App 进程。  
5. 任何 inline 路径改动必须重审：icache、页权限、PAC/BTI、发布顺序。  

---

*文档对应仓库实现日期锚点：与 `drm_hook_research.md` 2026-06-15 验证同架构；细节以当前 `arirang-submodule` 源码为准。*
