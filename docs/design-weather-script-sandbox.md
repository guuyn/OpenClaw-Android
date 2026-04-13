# Design: WeatherSkill Script Sandbox 改造

## 改造目标

将 WeatherSkill 从硬编码 HTTP 逻辑改为通过 ScriptSkill 的 JS 脚本沙箱执行，验证 `:script` 模块的实际可用性。

## 架构

```
WeatherSkill.initialize()
  → 创建 ScriptOrchestrator(context)
  → 从 assets/scripts/weather.js 加载脚本内容并缓存

WeatherTool.execute(location)
  → 构造 JS: var LOCATION = "xxx"; <cached script>
  → ScriptOrchestrator.execute(script, capabilities=["http"])
  → JS 通过 http.get() bridge 调用 wttr.in
  → 解析 ScriptResult → 转为 SkillResult
```

## JS 脚本内容

`assets/scripts/weather.js`:
- 接收全局变量 `LOCATION`
- 使用 `http.get('https://wttr.in/{LOCATION}?format=3')` 获取天气
- 返回 JSON: `{success: true, data: "Beijing: +20°C"}` 或 `{success: false, error: "..."}`

## 改造范围

| 文件 | 操作 |
|------|------|
| `assets/scripts/weather.js` | 新增 |
| `WeatherSkill.kt` | 重写：移除 OkHttp，改用 ScriptOrchestrator |
| `WeatherSkillTest.kt` | 重写：Mock ScriptOrchestrator 替代 OkHttpClient |

## 向后兼容

- Tool name 保持 `get_weather`
- Parameters 保持 `{ location: string, required }`
- SkillResult 格式不变
