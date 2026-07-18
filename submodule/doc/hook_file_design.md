# Hook helper 文件设计与结构

本文说明 `arirang-submodule/src/main/cpp/hook/` 下三个底层 hook helper 的
设计、职责边界和内部结构：

- `inline_hook.cpp`
- `symbol_resolver.cpp`
- `vtable_hook.cpp`

这三个文件都服务于 native DRM hook，但层级不同：

- `symbol_resolver.cpp` 负责在已加载 ELF 中找到目标函数符号。
- `vtable_hook.cpp` 负责扫描数据段并改写 vtable / method-table slot。
- `inline_hook.cpp` 负责旧式机器码入口 patch，以及少数不能用 vtable 方案覆盖的 fallback。

代码结构约束：实现文件中的控制流通过“小函数 + 早返回”压平，单个函数内
不设计超过三层的控制流嵌套。这里的嵌套指业务控制流，例如 `if`、`for`、
`while`、`switch` 等，不把 namespace 和函数定义本身计入。

## 1. `inline_hook.cpp`

### 1.1 职责

`inline_hook.cpp` 提供 AArch64 机器码级别的入口 hook：

- `inline_hook_install(target, handler, out_trampoline)`
  - 覆盖目标函数入口前 16 字节。
  - 构造 trampoline，让 hook handler 可以继续调用原函数逻辑。
- `inline_hook_branch(target, handler)`
  - 只覆盖目标入口第一条 32-bit `B` 指令。
  - 适合目标函数过短、无法安全覆盖 16 字节的场景。

该文件直接修改可执行代码页，因此风险高于 vtable hook。当前 DRM 主路径应优先
使用 `vtable_hook.cpp`；inline hook 保留为底层 fallback 能力。

### 1.2 核心机器码格式

标准 16 字节绝对跳转格式：

```text
LDR x16, [pc, #8]
BR  x16
<8-byte absolute handler address>
```

对应常量：

- `kLdrX16Pc8 = 0x58000050`
- `kBrX16 = 0xd61f0200`
- `kHookSize = 16`
- `kTrampolineSize = 32`

这样做的原因是 AArch64 单条普通跳转无法覆盖完整 64-bit 地址。先用
PC-relative literal load 把 handler 地址读入 `x16`，再用 `BR x16` 跳转。

### 1.3 主要结构

文件内部按职责分成以下 helper：

- 指令安全检查
  - `instruction_is_pc_relative`
  - `prologue_can_be_relocated`
- 页权限处理
  - `page_span_for`
  - `set_page_protection`
  - `unprotect_page`
  - `reprotect_page`
- trampoline 构造
  - `build_absolute_jump`
  - `prepare_install_trampoline`
  - `prepare_branch_trampoline`
- live patch 发布
  - `publish_live_absolute_jump`
  - `patch_target_with_absolute_jump`
  - `patch_target_with_branch`
- 近距离 trampoline 分配
  - `page_hint_near_target`
  - `try_map_nearby_page`
  - `allocate_nearby_trampoline`
  - `encode_branch_instruction`

公开函数只串联这些 helper，不直接承载复杂循环或多层条件判断。

### 1.4 `inline_hook_install` 数据流

1. 参数校验：`target`、`handler`、`out_trampoline` 都不能为空。
2. 检查目标函数前 4 条指令是否包含 PC-relative 指令。
3. `mmap` 一段 RW 匿名内存作为 trampoline。
4. 把目标函数前 16 字节复制到 trampoline。
5. 在 trampoline 末尾追加绝对跳转，跳回 `target + 16`。
6. 把 trampoline 权限改成 RX，并清 instruction cache。
7. 把目标函数页临时改成 RWX。
8. 按安全顺序发布入口绝对跳转。
9. 清目标函数 instruction cache。
10. 尝试把目标页恢复成 RX。
11. 返回 trampoline 给调用方。

如果第 4 到第 8 步中任意关键步骤失败，函数会 `munmap` 已分配 trampoline，
避免泄漏未发布的可执行内存。

### 1.5 PC-relative 指令拒绝策略

trampoline 通过“复制原始序言”实现原函数继续调用。以下指令被复制后会因为 PC
变化而改变语义，因此直接拒绝 hook：

- `ADR` / `ADRP`
- `B` / `BL`
- `B.cond`
- `CBZ` / `CBNZ`
- `TBZ` / `TBNZ`
- `LDR literal`

当前实现不做指令重写和重定位。这样能力较保守，但失败模式清晰。

### 1.6 live patch 顺序

覆盖正在运行进程的函数入口时，不能随意写 16 字节。`publish_live_absolute_jump`
的写入顺序是：

1. 写入 handler 地址低 32 位。
2. 写入 handler 地址高 32 位。
3. 写入 `BR x16`。
4. release fence。
5. 最后写入入口第一条 `LDR x16, [pc, #8]`。

这样新进入函数的线程只能看到两种状态：

- 旧的完整函数序言。
- 新的完整绝对跳转。

它不能看到“入口已经是新 LDR，但后面的地址还没写完”的半成品状态。这个设计
不能保护已经停在前 16 字节内部的线程；要完全避免这种竞态，需要暂停进程内
所有线程，当前实现没有这样做。

### 1.7 `inline_hook_branch` 数据流

`inline_hook_branch` 用单条 `B` 指令跳到附近 trampoline：

1. 参数和 page size 校验。
2. 在目标地址前后按页探测可用匿名页。
3. 要求 trampoline 地址落在 AArch64 `B imm26` 的 +/-128MB 范围内。
4. 在 trampoline 中写入完整 64-bit 绝对跳转到 handler。
5. 把 trampoline 改成 RX。
6. 编码 `B trampoline` 指令。
7. 把目标页临时改成 RWX。
8. 用单次 32-bit atomic store 写入入口 `B` 指令。
9. 清 instruction cache，并尝试恢复目标页 RX。

该模式不提供原函数 trampoline，因为它覆盖的只有第一条指令，调用方必须自行
模拟或替代原函数返回行为。

## 2. `symbol_resolver.cpp`

### 2.1 职责

`symbol_resolver.cpp` 在当前进程已经加载的共享库中查找动态符号：

```cpp
std::vector<ResolvedSymbol> resolve_symbols_by_substring(
    const char *library_path,
    const char *substring);
```

它只做解析，不做加载：

- 不调用普通 `dlopen` 加载新 so。
- 只通过 `dl_iterate_phdr` 遍历已映射对象。
- 匹配库路径时支持完整路径和 basename。
- 只返回 `STT_FUNC` / `STT_GNU_IFUNC` 类型的函数符号。
- 按 mangled name substring 过滤目标函数。

这种设计适合注入到 DRM HAL 后使用：目标 Widevine 库已由进程加载，resolver
只需要观察现有 ELF 状态，避免改变进程加载图。

### 2.2 内部数据结构

`IterContext`：

- `target_path`：调用方传入的目标库路径。
- `substring`：目标函数名片段。
- `results`：收集解析结果的 vector。

`SymbolTables`：

- `symbols`：`.dynsym` 起始地址。
- `strings`：`.dynstr` 起始地址。
- `count`：动态符号数量。

公开返回结构 `ResolvedSymbol` 在头文件中定义，包含：

- `name`：匹配到的符号名。
- `address`：运行时绝对函数地址。
- `library_base`：目标 so 的加载基址。

### 2.3 ELF 解析流程

`phdr_callback` 是 `dl_iterate_phdr` 的回调，流程如下：

1. 用 `path_matches` 判断当前对象是否为目标库。
2. 读取 `dlpi_addr` 作为 load base。
3. 用 `find_dynamic_segment` 找到 `PT_DYNAMIC` 段。
4. 用 `load_symbol_tables` 找到 `DT_SYMTAB` 和 `DT_STRTAB`。
5. 用 `resolve_symbol_count` 计算 dynsym 符号数量。
6. 用 `collect_matching_symbols` 过滤并写入结果。
7. 目标库已处理后返回 `1`，停止继续遍历。

### 2.4 动态指针重定位

`rebase_dynamic_pointer` 处理两类 linker 表现：

- `DT_*` 指针已经是运行时绝对地址。
- `DT_*` 指针仍是相对 load base 的虚拟地址。

判断规则是：

```text
raw < load_base => load_base + raw
raw >= load_base => raw
```

这保留了原实现对 Android linker 差异的兼容性。

### 2.5 符号数量计算

ELF 动态段没有统一字段直接表示 `.dynsym` 数量，因此 resolver 支持两种 hash：

- `DT_GNU_HASH`
  - 读取 bucket 数、symbol offset、bloom size。
  - 找出 bucket 中最大的符号下标。
  - 从 GNU chain 继续走到最低 bit 为 1 的结束项。
  - 最多扫描 `kMaxSymbolsScanned` 项，防止损坏 ELF 导致越界式长循环。
- `DT_HASH`
  - SysV hash 头部第二个字段 `nchain` 就是符号数量。

优先使用 GNU hash；没有 GNU hash 时回退到 SysV hash。

### 2.6 符号过滤

`symbol_name_matches` 负责过滤单个 `Elf64_Sym`：

1. 跳过无名符号。
2. 跳过 `st_value == 0` 的未定义或无效符号。
3. 只接受 `STT_FUNC` 和 `STT_GNU_IFUNC`。
4. 在 `.dynstr` 中取出符号名。
5. 用 `std::strstr` 做 substring 匹配。

匹配成功后，`make_resolved_symbol` 把 `load_base + st_value` 保存为运行时地址。

## 3. `vtable_hook.cpp`

### 3.1 职责

`vtable_hook.cpp` 通过改写数据段中的函数指针 slot 安装 hook：

```cpp
bool vtable_hook_install(
    const char *library_path,
    const char *symbol_substring,
    void *hook,
    std::vector<VtablePatch> *out_patches,
    bool try_relative);
```

它不改机器码，而是在目标库的可读、不可执行映射中扫描：

- 8 字节绝对函数指针 slot。
- 可选的 4 字节 signed relative slot。

这避开了 PAC、函数序言差异和指令重定位问题，是 DRM hook 的主路径。

### 3.2 内部数据结构

`MapEntry` 是 `/proc/self/maps` 的一行摘要：

- `start` / `end`：映射地址范围。
- `readable` / `writable` / `executable`：权限位。
- `path`：映射文件路径。

`ScanStats` 记录一次扫描结果：

- `scanned`：扫描的字节数。
- `patched`：成功改写的 slot 数。

头文件中的 `VtablePatch` 记录可恢复 patch：

- `slot_address`：被改写的 slot 地址。
- `original_function`：原函数绝对地址。
- `slot_type`：`kAbsolute64` 或 `kRelative32`。
- `slot_size`：slot 字节数。

### 3.3 `/proc/self/maps` 解析

`read_self_maps` 使用 `open(..., O_CLOEXEC)` 打开 maps，再用 `fdopen` 和
`fgets` 按行读取。每行交给 `parse_maps_line`：

```text
start-end perms offset dev inode path
```

解析后只保存后续扫描需要的字段。路径前导空格通过 `trim_leading_space` 去掉。

### 3.4 扫描范围

`is_library_data_map` 同时满足三个条件才允许扫描：

1. 映射路径与目标库匹配。
2. 映射可读。
3. 映射不可执行。

不要求映射本来可写，因为真实 vtable 常在 `.data.rel.ro`，重定位完成后会变成
只读。patch 时会临时 `mprotect(PROT_READ | PROT_WRITE)`。

### 3.5 绝对 slot 策略

绝对 vtable slot 是 8 字节函数指针。扫描流程：

1. 按 `sizeof(void *)` 对齐扫描起点。
2. 每 8 字节读取一个候选 slot。
3. 候选值必须等于 resolver 找到的目标函数地址。
4. `looks_like_absolute_vtable_slot` 要求左右相邻 slot 至少一个也指向可执行段。
5. 临时开放页写权限。
6. 记录 `VtablePatch`。
7. 把 slot 写成 hook 函数地址。
8. 如果原页不是 writable，恢复为只读。

邻居检查是关键误 patch 防线：孤立全局函数指针即使刚好等于目标函数，也不会被
当作 vtable slot 改写。

### 3.6 relative slot 策略

relative vtable slot 是 4 字节 signed offset，目标地址计算方式：

```text
destination = slot_address + int32_relative_offset
```

relative 扫描只在以下情况执行：

- 当前符号没有找到任何绝对 slot。
- 调用方允许 `try_relative`。

过滤流程：

1. 按 4 字节对齐扫描。
2. 计算候选 slot 的 destination。
3. destination 必须等于目标函数地址。
4. `looks_like_relative_vtable_slot` 要求邻近 rel32 slot 能解析到可执行段。
5. 计算 `hook_addr - slot_addr`，必须落在 int32 范围内。
6. 临时开放页写权限。
7. 记录 `VtablePatch`。
8. 写入新的 int32 relative offset。
9. 如果原页不是 writable，恢复为只读。

relative 策略故意作为 fallback，因为 blind rel32 扫描更容易命中 `.eh_frame`
或 CFI 数据；邻居检查用于降低误判概率。

### 3.7 页权限策略

`make_slot_writable_if_needed` 只在目标映射原本不可写时调用 `set_page_writable`。
`restore_slot_protection_if_needed` 也只恢复原本不可写的页。这样避免把本来 writable
的 `.data` 页误改成只读。

卸载时 `restore_patch` 会重新打开 slot 所在页写权限，写回原值，再恢复只读。
当前 `VtablePatch` 没有保存原始页权限；这延续了原实现行为。

### 3.8 安装流程

`vtable_hook_install` 的流程：

1. 参数校验。
2. 调用 `resolve_symbols_by_substring` 找目标符号。
3. 打印候选符号日志。
4. 读取 `/proc/self/maps`。
5. 对每个候选符号先调用 `scan_absolute_for_symbol`。
6. 如果绝对 slot 已 patch，跳过 relative 扫描。
7. 如果绝对 slot 未命中且 `try_relative == true`，调用 `scan_relative_for_symbol`。
8. 汇总扫描字节数和 patch 数。
9. 至少 patch 一个 slot 才返回 true。

### 3.9 卸载流程

`vtable_hook_uninstall` 遍历调用方保存的 `VtablePatch`：

- `kAbsolute64`：把 slot 写回原函数指针。
- `kRelative32`：重新计算 `original_function - slot_address`，写回 int32 offset。

任意 patch 恢复失败时返回 false，但会继续尝试恢复后续 patch。

## 4. 三者协作关系

典型 vtable hook 路径：

```text
arirang_drm_hook.cpp
  -> vtable_hook_install(...)
      -> resolve_symbols_by_substring(...)
          -> dl_iterate_phdr + dynsym scan
      -> read_self_maps()
      -> scan absolute slots
      -> optional scan relative slots
      -> write hook address into slot
```

fallback inline hook 路径：

```text
arirang_drm_hook.cpp
  -> inline_hook_install(...) 或 inline_hook_branch(...)
      -> validate prologue / branch range
      -> build trampoline
      -> mprotect target page
      -> publish instruction patch
```

主线应优先使用 vtable hook，因为它只改数据，不依赖目标函数机器码布局。
inline hook 只应在确实没有可改写函数表时使用。

## 5. 维护注意事项

- 修改 `inline_hook.cpp` 时，必须重新检查 instruction cache、页权限和并发写入顺序。
- 修改 `symbol_resolver.cpp` 时，必须保留“不加载新库”的语义。
- 修改 `vtable_hook.cpp` 时，必须先考虑误 patch 风险，再扩大扫描范围。
- 新增扫描策略时，应优先新增小 helper，不把复杂判断塞回公开入口函数。
- 对 relative vtable 的放宽需要设备日志或内存证据支持，不能只靠 substring 命中。

