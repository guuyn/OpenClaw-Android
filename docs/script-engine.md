# ScriptEngine 模块设计文档

> 版本：0.1.0 | 日期：2026-04-12 | 状态：原型阶段

## 1. 概述

ScriptEngine 是 OpenClaw Android 的动态脚本执行模块，独立为 `:script` Android Library Module。

**核心定位**：运行时动态生成的 Tool。LLM 根据用户任务生成 JS 脚本，沙箱执行后返回结果。

## 2. 架构

```
LLM 生成 JS 脚本
       │
       ▼
ScriptOrchestrator（入口）
       │
       ├── ScriptValidator ──→ 静态校验（禁止 import/require/eval）
       │
       ├── ScriptEngine ──→ Rhino JS 引擎（原型）/ QuickJS JNI（正式）
       │     │
       │     └── CapabilityBridge（JS → Android 能力桥接）
       │           ├── FileBridge     → fs.readFile/writeFile/list/exists
       │           ├── HttpBridge     → http.get/post
       │           └── MemoryBridge   → memory.recall/store（接口，主工程实现）
       │
       └── SandboxPolicy ──→ 超时/内存/路径限制
```

## 3. 模块结构

```
script/src/main/java/ai/openclaw.script/
├── CapabilityBridge.kt    # 桥接接口 + handleMethod 默认实现
├── SandboxPolicy.kt       # 安全策略数据类
├── ScriptResult.kt        # 执行结果数据类
├── ScriptValidator.kt     # 静态校验器
├── ScriptEngine.kt        # JS 执行引擎（Rhino 原型）
├── ScriptOrchestrator.kt  # 编排器：校验 → 注册 Bridge → 执行
└── bridge/
    ├── FileBridge.kt      # 文件操作（沙箱目录隔离）
    ├── HttpBridge.kt      # HTTP 请求（OkHttp）
    └── MemoryBridge.kt    # 记忆接口 + MemoryProvider 回调
```

## 4. 核心接口

### 4.1 CapabilityBridge

```kotlin
interface CapabilityBridge {
    val name: String
    fun getJsPrototype(): String
    fun handleMethod(method: String, argsJson: String): String
}
```

### 4.2 ScriptOrchestrator（入口）

```kotlin
class ScriptOrchestrator(context: Context) {
    fun execute(
        script: String,
        capabilities: List<String> = emptyList(),
        customBridges: List<CapabilityBridge> = emptyList()
    ): ScriptResult
}
```

### 4.3 MemoryBridge（回调模式）

```kotlin
class MemoryBridge(private val provider: MemoryProvider) : CapabilityBridge

fun interface MemoryProvider {
    suspend fun execute(method: String, args: String): String
}
```

## 5. 安全设计

### 5.1 静态校验（ScriptValidator）
- 禁止 `import`、`require`、`eval`、`new Function`
- 禁止访问 `java.`、`android.`、`Packages`、`process`、`global`、`window`、`document`
- 脚本长度上限 50KB

### 5.2 运行时沙箱（ScriptEngine）
- Rhino `initSafeStandardObjects()` 初始化
- 删除 `Packages`、`java`、`javax`、`org`、`com` 等全局对象
- 单次执行超时 10s（可配置）
- 每次执行创建独立 Context + 独立线程，执行完销毁

### 5.3 文件沙箱（FileBridge）
- 所有文件操作限制在 `script_sandbox/` 目录
- 路径穿越检测：`canonicalPath` 校验
- `../` 被拦截

## 6. JS API 参考

```javascript
// 文件操作
fs.readFile("notes.txt")       // → {content: "...", path: "notes.txt"}
fs.writeFile("out.json", data) // → {success: true, bytes: 42}
fs.list(".")                   // → {entries: [{name, isDirectory, size}]}
fs.exists("config.json")      // → {exists: true}

// HTTP 请求
http.get("https://api.example.com/data")
  // → {status: 200, body: "..."}
http.post(url, JSON.stringify(payload))
  // → {status: 200, body: "..."}

// 记忆系统（需要主工程提供 MemoryProvider）
memory.recall("上次的话题", 5)
  // → {results: [{content, similarity, type}]}
memory.store("今天学了新东西")
  // → {success: true}
```

## 7. 测试覆盖

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| ScriptValidatorTest | 22 | 正常脚本 + 空脚本 + 长度限制 + 18种危险模式 |
| ScriptEngineTest | 16 | 算术/字符串/数组/JSON + 控制流 + Bridge + 安全 + 超时 |
| FileBridgeTest | 16 | 读写文件 + 目录 + 存在性 + 路径穿越 + JSON工具 |
| ScriptResultTest | 6 | 数据类工厂方法 |
| SandboxPolicyTest | 2 | 默认值 + 自定义值 |

## 8. 依赖

| 依赖 | 用途 | 版本 |
|------|------|------|
| Rhino | JS 执行引擎（原型） | 1.7.15 |
| OkHttp | HTTP Bridge | 4.12.0 |
| kotlinx-coroutines | 异步支持 | 1.10.1 |

**正式版替换**：Rhino → QuickJS JNI（体积从 ~2MB 降至 ~500KB，性能提升 10x+）

## 9. 主工程集成

### 9.1 依赖

`app/build.gradle.kts` 添加：
```kotlin
implementation(project(":script"))
```

### 9.2 ScriptSkill

文件：`app/.../skill/builtin/ScriptSkill.kt`

- 实现 `Skill` 接口，注册到 `SkillManager`
- 提供一个 `execute_script` 工具
- LLM 通过调用 `script_execute_script` 触发脚本执行
- `ScriptOrchestrator` 在 `initialize()` 时创建

### 9.3 SkillManager 注册

```kotlin
registerSkill(ScriptSkill())
```

### 9.4 LLM 工具名

`script_execute_script`

参数：
- `script` (string, required) — JS 脚本代码
- `capabilities` (string, optional, default "fs,http") — 能力列表

---

## 10. 后续计划

- [ ] 替换 Rhino 为 QuickJS JNI
- [ ] 添加更多 Bridge（UiBridge、CalendarBridge、ContactBridge）
- [ ] 脚本缓存/复用机制
- [ ] LLM Prompt 模板（教 LLM 生成正确的 JS 脚本）
