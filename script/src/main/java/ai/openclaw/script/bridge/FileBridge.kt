package ai.openclaw.script.bridge

import android.content.Context
import ai.openclaw.script.CapabilityBridge
import java.io.File

/**
 * 文件操作 Bridge
 *
 * JS API:
 *   fs.readFile(path)     → {content, path}
 *   fs.writeFile(path, content) → {success, bytes}
 *   fs.list(dir)           → {entries: [{name, isDirectory, size}]}
 *   fs.exists(path)        → {exists}
 *
 * 所有操作限制在沙箱目录内。
 */
class FileBridge(
    private val context: Context?,
    private val sandboxDir: File
) : CapabilityBridge {

    override val name: String = "fs"

    override fun getJsPrototype(): String = """
        var fs = {
            readFile: function(path) { return JSON.parse(__nativeCall('fs.readFile', JSON.stringify({path: path}))); },
            writeFile: function(path, content) { return JSON.parse(__nativeCall('fs.writeFile', JSON.stringify({path: path, content: content}))); },
            list: function(dir) { return JSON.parse(__nativeCall('fs.list', JSON.stringify({dir: dir || '.'}))); },
            exists: function(path) { return JSON.parse(__nativeCall('fs.exists', JSON.stringify({path: path}))); }
        };
    """.trimIndent()

    fun handle(method: String, argsJson: String): String {
        return try {
            when (method) {
                "fs.readFile" -> readFile(argsJson)
                "fs.writeFile" -> writeFile(argsJson)
                "fs.list" -> listDir(argsJson)
                "fs.exists" -> checkExists(argsJson)
                else -> """{"error":"Unknown method: $method"}"""
            }
        } catch (e: Exception) {
            """{"error":"${jsonEscape(e.message ?: "Unknown error")}"}"""
        }
    }

    private fun resolveSafe(relativePath: String): File? {
        val resolved = File(sandboxDir, relativePath).canonicalPath
        if (!resolved.startsWith(sandboxDir.canonicalPath)) return null
        return File(resolved)
    }

    private fun readFile(argsJson: String): String {
        val path = extractField(argsJson, "path")
        val file = resolveSafe(path) ?: return """{"error":"Path traversal blocked"}"""
        if (!file.exists()) return """{"error":"File not found: $path"}"""
        val content = file.readText()
        return """{"content":${jsonEscape(content)},"path":"$path"}"""
    }

    private fun writeFile(argsJson: String): String {
        val path = extractField(argsJson, "path")
        val content = extractField(argsJson, "content")
        val file = resolveSafe(path) ?: return """{"error":"Path traversal blocked"}"""
        file.parentFile?.mkdirs()
        file.writeText(content)
        return """{"success":true,"bytes":${file.length()}}"""
    }

    private fun listDir(argsJson: String): String {
        val dir = extractField(argsJson, "dir").ifBlank { "." }
        val target = resolveSafe(dir) ?: return """{"error":"Path traversal blocked"}"""
        if (!target.exists() || !target.isDirectory) return """{"error":"Directory not found: $dir"}"""
        val entries = target.listFiles()?.map { f ->
            """{"name":"${f.name}","isDirectory":${f.isDirectory},"size":${f.length()}}"""
        } ?: emptyList()
        return """{"entries":[${entries.joinToString(",")}]}"""
    }

    private fun checkExists(argsJson: String): String {
        val path = extractField(argsJson, "path")
        val file = resolveSafe(path) ?: return """{"exists":false}"""
        return """{"exists":${file.exists()}}"""
    }
}

/** 从简单 JSON 中提取字段值（不依赖 org.json） */
internal fun extractField(json: String, field: String): String {
    val key = """"$field""""
    val idx = json.indexOf(key) ?: return ""
    if (idx < 0) return ""
    val colonIdx = json.indexOf(':', idx + key.length)
    if (colonIdx < 0) return ""
    var i = colonIdx + 1
    while (i < json.length && (json[i] == ' ' || json[i] == '\t')) i++
    if (i >= json.length) return ""
    return if (json[i] == '"') {
        val end = json.indexOf('"', i + 1)
        if (end < 0) "" else json.substring(i + 1, end)
    } else {
        val end = json.indexOfAny(charArrayOf(',', '}', ']'), i)
        if (end < 0) json.substring(i).trim() else json.substring(i, end).trim()
    }
}

/** JSON 字符串转义 */
internal fun jsonEscape(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append("\"")
    return sb.toString()
}
