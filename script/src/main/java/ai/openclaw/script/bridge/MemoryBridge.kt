package ai.openclaw.script.bridge

import ai.openclaw.script.CapabilityBridge

/**
 * 记忆系统 Bridge（接口）
 *
 * JS API:
 *   memory.recall(query, limit) → {results: [{content, similarity, type}]}
 *   memory.store(content)       → {success}
 *
 * 主工程通过 MemoryProvider 回调注入实际能力。
 */
class MemoryBridge(private val provider: MemoryProvider) : CapabilityBridge {

    override val name: String = "memory"

    override fun getJsPrototype(): String = """
        var memory = {
            recall: function(query, limit) { return JSON.parse(__nativeCall('memory.recall', JSON.stringify({query: query, limit: limit || 5}))); },
            store: function(content) { return JSON.parse(__nativeCall('memory.store', JSON.stringify({content: content}))); }
        };
    """.trimIndent()

    override fun handleMethod(method: String, argsJson: String): String {
        return try {
            when (method) {
                "memory.recall" -> {
                    val query = extractField(argsJson, "query")
                    val limit = extractField(argsJson, "limit").toIntOrNull() ?: 5
                    val result = kotlinx.coroutines.runBlocking {
                        provider.execute("recall", """{"query":"$query","limit":$limit}""")
                    }
                    result
                }
                "memory.store" -> {
                    val content = extractField(argsJson, "content")
                    val result = kotlinx.coroutines.runBlocking {
                        provider.execute("store", """{"content":"$content"}""")
                    }
                    result
                }
                else -> """{"error":"Unknown method: $method"}"""
            }
        } catch (e: Exception) {
            """{"error":"${jsonEscape(e.message ?: "Unknown error")}"}"""
        }
    }
}

/** 主工程实现此接口，提供实际的记忆能力 */
fun interface MemoryProvider {
    suspend fun execute(method: String, args: String): String
}
