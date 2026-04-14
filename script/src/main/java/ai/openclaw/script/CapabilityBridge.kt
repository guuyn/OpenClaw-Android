package ai.openclaw.script

import com.dokar.quickjs.QuickJs

/**
 * Capability Bridge — JS 脚本与 Android 能力的桥接接口
 *
 * JS 端可调用的能力通过实现此接口注册。
 * Rhino 模式: getJsPrototype() + handleMethod()
 * QuickJS 模式: registerBindings()
 */
interface CapabilityBridge {
    val name: String

    /** Rhino 模式: 返回 JS 原型代码 */
    fun getJsPrototype(): String

    /** Rhino 模式: 处理 JS 方法调用 */
    fun handleMethod(method: String, argsJson: String): String {
        return """{"error":"Method not implemented: $method"}"""
    }

    /** QuickJS 模式: 注册 Bridge 绑定 */
    fun registerBindings(quickJs: QuickJs) {
        // 默认空实现，子类覆盖
    }
}
