package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 文件读写技能 — 支持在 Android 文件系统中读取和写入文件。
 *
 * ## 存储策略（Android 10+ Scoped Storage 兼容）
 *
 * Target SDK 35 下，不能直接访问 /sdcard/ 根目录。
 * 使用 **应用专属存储**（App-Specific Storage），无需特殊权限。
 *
 * 外部目录: /sdcard/Android/data/ai.openclaw.android/files/openclaw/
 *   - 用户可通过文件管理器访问
 *   - 卸载时自动清理
 *
 * 内部目录: /data/data/ai.openclaw.android/files/openclaw/
 *   - 应用私有，其他应用不可见
 *
 * ## 路径规则
 * - `~` 或 `~/xxx` → 外部目录（用户可访问）
 * - `~/internal/xxx` → 内部目录（应用私有）
 * - 相对路径 `notes.txt` → 外部目录根
 * - `/sdcard/...` 绝对路径 → 重定向到外部目录
 */
class FileSkill(private val context: Context) : Skill {
    override val id = "file"
    override val name = "文件读写"
    override val description = "读取和写入文件"
    override val version = "1.1.0"

    override val instructions = """
# 文件读写技能

读取和写入文件内容。Android 10+ Scoped Storage 兼容。

## 可用工具
- `read_file` — 读取文本文件内容
- `write_file` — 写入内容到文件
- `list_dir` — 列出目录内容

## 路径说明
| 路径格式 | 实际位置 |
|---------|---------|
| `~` 或 `~/` | /sdcard/Android/data/.../files/openclaw/ |
| `~/notes.txt` | 外部目录下的 notes.txt |
| `~/internal/config.json` | 内部存储的 config.json |
| `notes.txt` | 外部目录根下的 notes.txt |

## 限制
- 单次读取最大 500KB
- 写入自动创建父目录
- 不支持二进制文件
""".trimIndent()

    /**
     * 外部专属目录
     */
    private val externalRoot: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "openclaw")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /**
     * 内部专属目录
     */
    private val internalRoot: File by lazy {
        val dir = File(context.filesDir, "openclaw")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    override val tools: List<SkillTool> = listOf(
        ReadFileTool(),
        WriteFileTool(),
        ListDirTool()
    )

    // ==================== read_file ====================

    private inner class ReadFileTool : SkillTool {
        override val name = "read_file"
        override val description = "读取文本文件内容"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "文件路径，如 ~/notes.txt",
                required = true
            ),
            "max_chars" to SkillParam(
                type = "number",
                description = "最大读取字符数，默认 50000",
                required = false,
                default = 50000
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val path = params["path"] as? String
            if (path == null || path.isBlank()) {
                return SkillResult(false, "", "缺少 path 参数")
            }

            val maxChars = (params["max_chars"] as? Number)?.toInt() ?: 50000

            return try {
                val file = resolvePath(path)
                if (!file.exists()) {
                    val hint = suggestFiles(file.parentFile ?: externalRoot)
                    return SkillResult(false, "", "文件不存在: ${file.absolutePath}$hint")
                }
                if (!file.isFile) {
                    return SkillResult(false, "", "路径不是文件: ${file.absolutePath}")
                }
                if (file.length() > maxChars * 2L) {
                    return SkillResult(false, "", "文件过大 (${formatSize(file.length())})，超过读取限制")
                }

                val content = file.readText(Charsets.UTF_8).take(maxChars)
                val info = buildString {
                    appendLine("📄 ${file.name}")
                    appendLine("路径: ${file.absolutePath}")
                    appendLine("大小: ${formatSize(file.length())}")
                    appendLine("字符: ${content.length}")
                    appendLine("---")
                }
                SkillResult(true, info + content)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: IOException) {
                SkillResult(false, "", "读取失败: ${e.message}")
            } catch (e: Exception) {
                SkillResult(false, "", "错误: ${e.message}")
            }
        }
    }

    // ==================== write_file ====================

    private inner class WriteFileTool : SkillTool {
        override val name = "write_file"
        override val description = "写入内容到文件，自动创建父目录"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "文件路径，如 ~/notes.txt",
                required = true
            ),
            "content" to SkillParam(
                type = "string",
                description = "要写入的内容",
                required = true
            ),
            "append" to SkillParam(
                type = "boolean",
                description = "追加模式（默认 false，覆盖写入）",
                required = false,
                default = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val path = params["path"] as? String
            val content = params["content"] as? String
            if (path == null || path.isBlank()) return SkillResult(false, "", "缺少 path 参数")
            if (content == null) return SkillResult(false, "", "缺少 content 参数")

            val append = params["append"] as? Boolean ?: false

            return try {
                val file = resolvePath(path)
                file.parentFile?.mkdirs()

                if (append && file.exists()) {
                    file.appendText(content, Charsets.UTF_8)
                } else {
                    file.writeText(content, Charsets.UTF_8)
                }

                val mode = if (append) "追加" else "覆盖"
                SkillResult(
                    true,
                    "✅ 写入成功（${mode}模式）\n路径: ${file.absolutePath}\n大小: ${formatSize(file.length())}\n内容: ${content.length} 字符"
                )
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: IOException) {
                SkillResult(false, "", "写入失败: ${e.message}")
            } catch (e: Exception) {
                SkillResult(false, "", "错误: ${e.message}")
            }
        }
    }

    // ==================== list_dir ====================

    private inner class ListDirTool : SkillTool {
        override val name = "list_dir"
        override val description = "列出目录内容"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "目录路径，默认 ~",
                required = false,
                default = "~"
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val rawPath = params["path"] as? String ?: "~"

            return try {
                val dir = resolvePath(rawPath)
                if (!dir.exists()) return SkillResult(false, "", "目录不存在: ${dir.absolutePath}")
                if (!dir.isDirectory) return SkillResult(false, "", "不是目录: ${dir.absolutePath}")

                val files = dir.listFiles()?.toList() ?: emptyList()
                if (files.isEmpty()) {
                    return SkillResult(true, "📁 目录为空: ${dir.absolutePath}\n\n💡 使用 file_write_file 创建文件")
                }

                val lines = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { f ->
                        val icon = if (f.isDirectory) "📁" else "📄"
                        val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                        "$icon ${f.name}$size"
                    }

                val isExt = dir.canonicalPath.startsWith(externalRoot.canonicalPath)
                val storageLabel = if (isExt) "外部（用户可访问）" else "内部（应用私有）"
                val header = buildString {
                    appendLine("📁 ${dir.absolutePath}")
                    appendLine("💾 $storageLabel")
                    appendLine("📊 ${files.size} 项")
                    appendLine("---")
                }
                SkillResult(true, header + lines.joinToString("\n"))
            } catch (e: Exception) {
                SkillResult(false, "", "错误: ${e.message}")
            }
        }
    }

    // ==================== 路径解析 ====================

    /**
     * 解析路径到对应的 File 对象
     */
    private fun resolvePath(raw: String): File {
        val path = raw.trim()

        return when {
            // ~/internal/xxx → 内部目录
            path.startsWith("~/internal") -> {
                val rel = path.removePrefix("~/internal").removePrefix("/")
                if (rel.isEmpty()) internalRoot else File(internalRoot, rel)
            }
            // ~/xxx → 外部目录
            path.startsWith("~") -> {
                val rel = path.removePrefix("~").removePrefix("/")
                if (rel.isEmpty()) externalRoot else File(externalRoot, rel)
            }
            // /sdcard/... 或 /storage/... → 重定向到外部目录
            path.startsWith("/sdcard/") || path.startsWith("/storage/") -> {
                val rel = path
                    .removePrefix("/sdcard/")
                    .removePrefix("/storage/emulated/0/")
                if (rel.isEmpty()) externalRoot else File(externalRoot, rel)
            }
            // 其他绝对路径 → 外部目录
            path.startsWith("/") -> File(externalRoot, path.removePrefix("/"))
            // 相对路径 → 外部目录
            else -> if (path.isEmpty()) externalRoot else File(externalRoot, path)
        }
    }

    // ==================== 工具方法 ====================

    private fun suggestFiles(dir: File): String {
        val files = dir.listFiles()?.filter { it.isFile }?.take(10) ?: emptyList()
        return if (files.isNotEmpty()) {
            "\n该目录下有: ${files.joinToString(", ") { it.name }}"
        } else {
            ""
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ==================== Skill 生命周期 ====================

    override fun initialize(context: SkillContext) {
        externalRoot.mkdirs()
        internalRoot.mkdirs()
        Log.i("FileSkill", "Initialized. External: ${externalRoot.absolutePath}, Internal: ${internalRoot.absolutePath}")
    }

    override fun cleanup() {}
}
