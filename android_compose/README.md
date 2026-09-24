# A2UI Android Compose Renderer

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-29%2B-brightgreen.svg)](https://android-arsenal.com/api?level=29)

**[English](README_EN.md)** | **中文**

> **基于 [A2UI 协议](https://github.com/google/A2UI)** - 一个功能完整的 Android Jetpack Compose 实现的 A2UI (Agent to UI) 协议渲染器，允许 Android 应用程序动态渲染由 A2UI 后端代理生成的用户界面。

## 📖 目录

- [概述](#概述)
- [功能特性](#功能特性)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [安装集成](#安装集成)
- [核心功能](#核心功能)
- [组件列表](#组件列表)
- [使用示例](#使用示例)
- [主题定制](#主题定制)
- [错误处理](#错误处理)
- [网络传输](#网络传输)
- [可访问性](#可访问性)
- [性能优化](#性能优化)
- [测试覆盖](#测试覆盖)
- [API 参考](#api-参考)
- [注意事项](#注意事项)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

## 概述

A2UI Android Compose Renderer 是 A2UI 协议在 Android 平台上的完整实现，使用现代的 Jetpack Compose 技术栈构建。它支持 A2UI v0.10 协议的所有核心功能，包括动态组件渲染、数据绑定、主题定制、网络传输等。

### 为什么选择 A2UI Compose？

- **声明式 UI**: 基于 Jetpack Compose，采用现代声明式 UI 范式
- **响应式更新**: 内置状态管理，支持高效的数据绑定和 UI 更新
- **高度可定制**: 支持自定义组件、主题、验证规则等
- **完整兼容**: 支持 Android 10+ (API 29+)
- **性能优化**: 使用 `rememberSaveable`、`key()` 等技术优化重组性能
- **可访问性**: 内置 WCAG A 级可访问性支持

## 功能特性

### ✅ 核心功能

| 功能 | 描述 | 状态 |
|------|------|------|
| A2UI v0.10 协议 | 完整支持 createSurface、updateComponents、updateDataModel、deleteSurface | ✅ |
| 动态组件渲染 | 20+ 标准组件，支持自定义组件注册 | ✅ |
| 数据绑定 | 单向/双向数据绑定，路径表达式 | ✅ |
| 输入验证 | required、email、url、phone、regex 等 | ✅ |
| 主题定制 | 动态颜色、深色模式、自定义主题 | ✅ |
| 网络传输 | WebSocket、SSE (Server-Sent Events) | ✅ |
| 状态持久化 | 配置变化时自动保存/恢复状态 | ✅ |
| 错误处理 | 全局错误处理器、错误展示组件 | ✅ |
| 可访问性 | TalkBack 支持、语义化标签、触摸目标 | ✅ |
| 动画效果 | Modal 动画、过渡动画 | ✅ |

### 📦 支持的组件

| 组件 | 描述 | 可访问性 |
|------|------|----------|
| **Text** | 文本显示，支持 h1/h2/h3/title/subtitle/body/caption/label 变体 | ✅ |
| **Button** | 按钮，支持 primary/secondary/text 变体 | ✅ |
| **TextField** | 文本输入框，支持验证规则 | ✅ |
| **CheckBox** | 复选框 | ✅ |
| **Switch** | 开关 | ✅ |
| **Slider** | 滑块 | ✅ |
| **ChoicePicker** | 单选/多选选择器 | ✅ |
| **Dropdown** | 下拉选择框 | ✅ |
| **Card** | 卡片容器 | ✅ |
| **Row** | 水平布局容器 | ✅ |
| **Column** | 垂直布局容器 | ✅ |
| **List** | 滚动列表 | ✅ |
| **Tabs** | 标签页 | ✅ |
| **Modal** | 模态对话框（带动画） | ✅ |
| **Image** | 图片显示（Coil 加载） | ✅ |
| **Icon** | 图标显示 | ✅ |
| **Divider** | 分隔线 | ✅ |
| **Spacer** | 间距 | ✅ |
| **ProgressBar** | 进度条 | ✅ |
| **DateTimeInput** | 日期时间选择器 | ✅ |
| **Video** | 视频播放器（占位符） | ✅ |
| **AudioPlayer** | 音频播放器（占位符） | ✅ |
| **Surface** | 基础容器 | ✅ |

## 架构设计

### 项目结构

```
android_compose/
├── src/
│   ├── main/
│   │   ├── java/org/a2ui/compose/
│   │   │   ├── data/                    # 数据层
│   │   │   │   ├── A2UIMessage.kt       # 消息类型定义
│   │   │   │   ├── DataModelProcessor.kt # 数据模型处理器
│   │   │   │   └── DataModelState.kt    # 数据模型状态
│   │   │   ├── rendering/               # 渲染层
│   │   │   │   ├── A2UIRenderer.kt      # 主渲染器
│   │   │   │   └── ComponentRegistry.kt # 组件注册表
│   │   │   ├── transport/               # 网络传输层
│   │   │   │   ├── A2UITransport.kt     # 传输接口
│   │   │   │   └── NetworkTransport.kt  # WebSocket/SSE 实现
│   │   │   ├── theme/                   # 主题层
│   │   │   │   └── A2UITheme.kt         # 主题配置
│   │   │   ├── error/                   # 错误处理层
│   │   │   │   └── ErrorHandler.kt      # 错误处理器
│   │   │   ├── service/                 # 服务层
│   │   │   │   └── A2UIService.kt       # 高级服务 API
│   │   │   └── example/                 # 示例代码
│   │   │       ├── A2UIDemoActivity.kt  # Demo 应用
│   │   │       └── A2UISampleActivity.kt # 示例活动
│   │   ├── res/                         # 资源文件
│   │   │   └── values/
│   │   │       ├── colors.xml
│   │   │       ├── strings.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   └── test/                            # 单元测试
│       └── java/org/a2ui/compose/
│           ├── data/
│           │   ├── DataModelStateTest.kt
│           │   └── DataModelProcessorTest.kt
│           ├── rendering/
│           │   └── A2UIRendererTest.kt
│           └── theme/
│               └── A2UIThemeTest.kt
├── build.gradle.kts                     # 构建配置
└── README.md                            # 本文档
```

### 核心架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      A2UI Agent (Backend)                    │
└─────────────────────────┬───────────────────────────────────┘
                          │ A2UI Messages (JSON)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                     Transport Layer                          │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │ WebSocket       │  │ SSE             │                   │
│  │ Transport       │  │ Transport       │                   │
│  └────────┬────────┘  └────────┬────────┘                   │
└───────────┼─────────────────────┼───────────────────────────┘
            │                     │
            ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                     A2UI Renderer                            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Message Processor                                     │    │
│  │  • CreateSurface  • UpdateComponents                 │    │
│  │  • UpdateDataModel  • DeleteSurface                  │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │                                   │
│  ┌───────────────────────▼─────────────────────────────┐    │
│  │ Data Model Processor                                 │    │
│  │  • State Management  • Data Binding                  │    │
│  │  • Validation  • Dynamic Value Resolution            │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │                                   │
│  ┌───────────────────────▼─────────────────────────────┐    │
│  │ Component Registry                                   │    │
│  │  • Standard Components  • Custom Components          │    │
│  │  • Accessibility  • Animations                       │    │
│  └───────────────────────┬─────────────────────────────┘    │
└──────────────────────────┼──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                         │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │ Text    │ │ Button  │ │ TextField│ │ Card   │ ...        │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
└─────────────────────────────────────────────────────────────┘
```

## 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- Android SDK 29+ (Android 10)
- Kotlin 2.3.0
- JDK 17

### 5 分钟快速集成

```kotlin
// 1. 在 Activity 中创建渲染器
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val renderer = rememberA2UIRenderer()
            
            // 处理 A2UI 消息
            renderer.processMessage("""
                {
                    "version": "v0.10",
                    "createSurface": {
                        "surfaceId": "hello",
                        "catalogId": "https://a2ui.org/catalog.json"
                    }
                }
            """)
            
            renderer.processMessage("""
                {
                    "version": "v0.10",
                    "updateComponents": {
                        "surfaceId": "hello",
                        "components": [
                            {
                                "id": "root",
                                "component": "Text",
                                "text": "Hello, A2UI!"
                            }
                        ]
                    }
                }
            """)
            
            // 渲染界面
            A2UISurface(surfaceId = "hello")
        }
    }
}
```

## 安装集成

### 方式一：作为模块集成

1. **克隆仓库**
   ```bash
   git clone https://github.com/your-org/A2UI.git
   cd A2UI
   ```

2. **添加模块到项目**

   在项目的 `settings.gradle.kts` 中添加：
   ```kotlin
   include(":compose")
   project(":compose").projectDir = file("path/to/A2UI/compose")
   ```

3. **添加依赖**

   在 app 模块的 `build.gradle.kts` 中添加：
   ```kotlin
   dependencies {
       implementation(project(":compose"))
   }
   ```

### 方式二：复制源码

直接将 `compose/src/main/java/org/a2ui/compose` 目录复制到您的项目中。

### 依赖项

项目依赖以下库（已在 `build.gradle.kts` 中配置）：

```kotlin
// Jetpack Compose
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")

// Kotlin
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// 图片加载
implementation("io.coil-kt:coil-compose:2.5.0")

// 网络请求
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
```

## 核心功能

### 1. 消息处理

A2UI 渲染器通过处理 JSON 消息来更新界面：

```kotlin
val renderer = A2UIRenderer()

// 创建 Surface
renderer.processMessage("""
    {
        "version": "v0.10",
        "createSurface": {
            "surfaceId": "my_surface",
            "catalogId": "https://a2ui.org/catalog.json",
            "theme": { "primaryColor": "#6200EE" }
        }
    }
""")

// 更新组件
renderer.processMessage("""
    {
        "version": "v0.10",
        "updateComponents": {
            "surfaceId": "my_surface",
            "components": [ /* 组件定义 */ ]
        }
    }
""")

// 更新数据模型
renderer.processMessage("""
    {
        "version": "v0.10",
        "updateDataModel": {
            "surfaceId": "my_surface",
            "path": "/user/name",
            "value": "John Doe"
        }
    }
""")

// 删除 Surface
renderer.processMessage("""
    {
        "version": "v0.10",
        "deleteSurface": {
            "surfaceId": "my_surface"
        }
    }
""")
```

### 2. 数据绑定

支持路径表达式进行数据绑定：

```kotlin
// 组件定义中使用路径绑定
{
    "id": "name_field",
    "component": "TextField",
    "label": "Name",
    "value": { "path": "/user/name" },
    "placeholder": "Enter your name"
}

// 支持嵌套路径
{
    "id": "city_field",
    "component": "Text",
    "text": { "path": "/user/address/city" }
}
```

### 3. 输入验证

内置多种验证规则：

```kotlin
{
    "id": "email_field",
    "component": "TextField",
    "label": "Email",
    "value": { "path": "/form/email" },
    "required": true,
    "checks": [
        {
            "call": "email",
            "args": {},
            "message": "Please enter a valid email"
        }
    ]
}
```

**支持的验证规则**：

| 规则 | 描述 | 参数 |
|------|------|------|
| `required` | 必填验证 | - |
| `email` | 邮箱格式验证 | - |
| `url` | URL 格式验证 | - |
| `phone` | 电话号码验证 | - |
| `minLength` | 最小长度验证 | `min: Int` |
| `maxLength` | 最大长度验证 | `max: Int` |
| `regex` | 正则表达式验证 | `pattern: String` |
| `numeric` | 数字验证 | `min: Number`, `max: Number` |

### 4. 动作处理

处理用户交互事件：

```kotlin
val renderer = A2UIRenderer()

renderer.setActionHandler(object : ActionHandler {
    override fun onAction(surfaceId: String, actionName: String, context: Map<String, Any>) {
        when (actionName) {
            "submit_form" -> {
                val formData = renderer.getDataModel(surfaceId)?.getDataSnapshot()
                // 处理表单提交
            }
        }
    }
    
    override fun openUrl(url: String) {
        // 打开 URL
    }
    
    override fun showToast(message: String) {
        // 显示 Toast
    }
})
```

## 组件列表

### Text - 文本组件

```json
{
    "id": "title",
    "component": "Text",
    "text": "Hello World",
    "variant": "h2"
}
```

**variant 可选值**：`h1`, `h2`, `h3`, `title`, `subtitle`, `body`, `caption`, `label`

### Button - 按钮组件

```json
{
    "id": "submit_btn",
    "component": "Button",
    "text": "Submit",
    "variant": "primary",
    "action": {
        "event": {
            "name": "submit",
            "context": { "formId": "contact" }
        }
    }
}
```

**variant 可选值**：`primary`, `secondary`, `text`

### TextField - 文本输入

```json
{
    "id": "email",
    "component": "TextField",
    "label": "Email Address",
    "value": { "path": "/form/email" },
    "placeholder": "Enter your email",
    "required": true,
    "checks": [
        { "call": "email", "args": {}, "message": "Invalid email format" }
    ]
}
```

### List - 列表组件

```json
{
    "id": "item_list",
    "component": "List",
    "children": {
        "path": "/items",
        "componentId": "list_item"
    }
}
```

### Modal - 模态对话框

```json
{
    "id": "confirm_dialog",
    "component": "Modal",
    "child": "dialog_content",
    "action": {
        "event": { "name": "dismiss" }
    }
}
```

## 使用示例

### 完整表单示例

```kotlin
@Composable
fun ContactFormScreen() {
    val renderer = rememberA2UIRenderer()
    
    DisposableEffect(Unit) {
        // 创建 Surface
        renderer.processMessage("""
            {
                "version": "v0.10",
                "createSurface": {
                    "surfaceId": "contact_form",
                    "catalogId": "https://a2ui.org/catalog.json",
                    "theme": { "primaryColor": "#6200EE" }
                }
            }
        """)
        
        // 定义组件
        renderer.processMessage("""
            {
                "version": "v0.10",
                "updateComponents": {
                    "surfaceId": "contact_form",
                    "components": [
                        {"id": "root", "component": "Card", "child": "form"},
                        {"id": "form", "component": "Column", "children": ["title", "name", "email", "message", "submit"], "align": "stretch"},
                        {"id": "title", "component": "Text", "text": "Contact Us", "variant": "h2"},
                        {"id": "name", "component": "TextField", "label": "Name", "value": {"path": "/name"}, "required": true},
                        {"id": "email", "component": "TextField", "label": "Email", "value": {"path": "/email"}, "required": true, "checks": [{"call": "email", "args": {}, "message": "Invalid email"}]},
                        {"id": "message", "component": "TextField", "label": "Message", "value": {"path": "/message"}, "variant": "longText"},
                        {"id": "submit", "component": "Button", "text": "Send", "action": {"event": {"name": "submit_contact"}}}
                    ]
                }
            }
        """)
        
        onDispose {
            renderer.processMessage("""{"version": "v0.10", "deleteSurface": {"surfaceId": "contact_form"}}""")
        }
    }
    
    RenderSurface(renderer, "contact_form")
}
```

## 主题定制

### 使用主题配置

```kotlin
@Composable
fun ThemedApp() {
    val themeConfig = A2UIThemeConfig(
        primaryColor = "#6200EE",
        secondaryColor = "#03DAC6",
        backgroundColor = "#FFFFFF",
        surfaceColor = "#FFFFFF",
        errorColor = "#B00020",
        darkMode = false,
        borderRadius = 12,
        fontFamily = "Roboto"
    )
    
    A2UITheme(config = themeConfig) {
        // 您的 A2UI 界面
        A2UISurface(surfaceId = "main")
    }
}
```

### 动态主题切换

```kotlin
@Composable
fun DynamicThemeApp() {
    var isDarkMode by remember { mutableStateOf(false) }
    
    val themeConfig = A2UIThemeConfig(
        primaryColor = if (isDarkMode) "#BB86FC" else "#6200EE",
        darkMode = isDarkMode
    )
    
    A2UITheme(config = themeConfig) {
        Column {
            Switch(
                checked = isDarkMode,
                onCheckedChange = { isDarkMode = it }
            )
            A2UISurface(surfaceId = "main")
        }
    }
}
```

## 错误处理

### 全局错误处理器

```kotlin
val errorHandler = DefaultErrorHandler()

val renderer = A2UIRenderer(
    logger = DefaultLogger(),
    errorHandler = errorHandler
)

// 显示错误
@Composable
fun ErrorAwareScreen() {
    val errors by remember { derivedStateOf { errorHandler.errors } }
    
    Column {
        // 显示错误横幅
        errors.forEachIndexed { index, errorInfo ->
            ErrorBanner(
                errorInfo = errorInfo,
                onDismiss = { errorHandler.dismissError(index) },
                onRetry = errorInfo.recoveryAction
            )
        }
        
        // 主界面
        A2UISurface(surfaceId = "main")
    }
}
```

### 错误类型

| 错误类型 | 描述 |
|---------|------|
| `ParseError` | JSON 解析错误 |
| `NetworkError` | 网络连接错误 |
| `ComponentError` | 组件渲染错误 |
| `ValidationError` | 输入验证错误 |
| `StateError` | 状态管理错误 |
| `UnknownError` | 未知错误 |

## 网络传输

### WebSocket 连接

```kotlin
val transport = WebSocketTransport(
    url = "wss://your-server.com/a2ui",
    reconnectEnabled = true,
    reconnectDelayMs = 3000
)

// 连接
scope.launch {
    transport.connect()
    
    transport.messages.collect { message ->
        renderer.processMessage(message)
    }
}

// 发送消息
transport.send("""{"action": "ping"}""")
```

### SSE 连接

```kotlin
val transport = SSETransport(
    url = "https://your-server.com/a2ui/stream",
    reconnectEnabled = true
)

scope.launch {
    transport.connect()
    
    transport.messages.collect { message ->
        renderer.processMessage(message)
    }
}
```

## 可访问性

### WCAG A 级合规

渲染器内置以下可访问性支持：

- **语义化标签**: 所有组件都有 `contentDescription`
- **角色标识**: Button、CheckBox、Switch 等有正确的 `Role`
- **状态描述**: CheckBox、Switch 有状态描述
- **实时区域**: 错误消息使用 `LiveRegionMode.Polite`
- **触摸目标**: 所有可点击元素最小 48dp

### 自定义可访问性

```kotlin
// 组件会自动处理可访问性
// 如需自定义，可以在组件定义中添加：
{
    "id": "custom_button",
    "component": "Button",
    "text": "Submit",
    "accessibilityLabel": "Submit the contact form"
}
```

## 性能优化

### 已实施的优化

1. **状态持久化**: 使用 `rememberSaveable` 保存状态
2. **列表优化**: LazyColumn 使用 `key` 参数
3. **条件更新**: `LaunchedEffect` 条件检查避免不必要更新
4. **组件复用**: 通过 ComponentRegistry 实现组件复用

### 性能最佳实践

```kotlin
// ✅ 推荐：使用 rememberA2UIRenderer
val renderer = rememberA2UIRenderer()

// ✅ 推荐：使用 DisposableEffect 清理资源
DisposableEffect(surfaceId) {
    // 初始化
    onDispose {
        // 清理
    }
}

// ✅ 推荐：使用 key 稳定组件身份
key(component.id) {
    render(component, context)
}
```

## 测试覆盖

### 单元测试

项目包含完整的单元测试：

```
src/test/java/org/a2ui/compose/
├── data/
│   ├── DataModelStateTest.kt        # 9 个测试
│   └── DataModelProcessorTest.kt    # 13 个测试
├── rendering/
│   └── A2UIRendererTest.kt          # 16 个测试
└── theme/
    └── A2UIThemeTest.kt             # 11 个测试
```

### 运行测试

```bash
# 运行所有单元测试
./gradlew :compose:test

# 运行特定测试类
./gradlew :compose:test --tests "org.a2ui.compose.rendering.A2UIRendererTest"
```

## API 参考

### A2UIRenderer

主渲染器类，负责处理消息和管理界面状态。

```kotlin
class A2UIRenderer(
    logger: A2UILogger = DefaultLogger(),
    errorHandler: A2UIErrorHandler? = null
) {
    // 处理 A2UI 消息
    fun processMessage(message: String): Result<Unit>
    
    // 获取 Surface 上下文
    fun getSurfaceContext(surfaceId: String): SurfaceContext?
    
    // 获取组件
    fun getComponent(surfaceId: String, componentId: String): Component?
    
    // 获取数据模型
    fun getDataModel(surfaceId: String): DataModelState?
    
    // 设置动作处理器
    fun setActionHandler(handler: ActionHandler?)
    
    // 保存/恢复状态
    fun saveState(): SavedRendererState
    fun restoreState(state: SavedRendererState)
    
    // 清理资源
    fun dispose()
}
```

### ComponentRegistry

组件注册表，管理所有组件的渲染。

```kotlin
class ComponentRegistry(renderer: A2UIRenderer) {
    // 注册自定义组件
    fun registerCustomComponent(
        name: String,
        factory: @Composable (Component, SurfaceContext) -> Unit
    )
    
    // 移除自定义组件
    fun unregisterCustomComponent(name: String)
    
    // 渲染组件
    @Composable
    fun render(component: Component, context: SurfaceContext)
}
```

### A2UITheme

主题配置 Composable。

```kotlin
@Composable
fun A2UITheme(
    config: A2UIThemeConfig = A2UIThemeConfig(),
    darkTheme: Boolean = config.darkMode ?: isSystemInDarkTheme(),
    content: @Composable () -> Unit
)

data class A2UIThemeConfig(
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val backgroundColor: String? = null,
    val surfaceColor: String? = null,
    val textColor: String? = null,
    val errorColor: String? = null,
    val darkMode: Boolean? = null,
    val borderRadius: Int = 8,
    val fontFamily: String? = null
)
```

## 注意事项

### 兼容性

- **最低 SDK**: Android 10 (API 29)
- **编译 SDK**: Android 15 (API 35)
- **Kotlin 版本**: 2.3.0

### 已知限制

1. **Video 组件**: 当前为占位符实现，需要集成 ExoPlayer
2. **AudioPlayer 组件**: 当前为占位符实现，需要集成 MediaPlayer
3. **Markdown 渲染**: 尚未实现

### 迁移指南

从早期版本迁移：

```kotlin
// 旧版本
val renderer = A2UIRenderer()
renderer.processMessage(message)

// 新版本（推荐）
val renderer = rememberA2UIRenderer()
renderer.processMessage(message)
```

### 调试技巧

```kotlin
// 启用详细日志
val logger = object : A2UILogger {
    override fun log(level: A2UILogLevel, message: String) {
        Log.d("A2UI", "[$level] $message")
    }
}

val renderer = A2UIRenderer(logger = logger)
```

## 贡献指南

我们欢迎所有形式的贡献！请查看 [CONTRIBUTING.md](../CONTRIBUTING.md) 了解详情。

### 开发环境设置

1. Fork 并克隆仓库
2. 在 Android Studio 中打开项目
3. 运行 `./gradlew :compose:build` 验证构建

### 代码风格

- 遵循 Kotlin 官方代码风格
- 使用 4 空格缩进
- 所有公共 API 必须有 KDoc 注释

## 许可证

本项目采用 Apache 2.0 许可证 - 详见 [LICENSE](../LICENSE) 文件。
