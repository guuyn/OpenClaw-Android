package ai.openclaw.script

/**
 * 能力桥接接口
 *
 * JS 脚本通过 __nativeCall('bridgeName.method', args) 调用宿主能力。
 * 每种能力（fs、http、memory）实现此接口。
 */
interface CapabilityBridge {
    val name: String

    /** 返回注入到 JS 环境的原型代码 */
    fun getJsPrototype(): String

    /** 处理方法调用，返回 JSON 字符串 */
    fun handleMethod(method: String, argsJson: String): String {
        return """{"error":"Method not implemented: $method"}"""
    }
}
