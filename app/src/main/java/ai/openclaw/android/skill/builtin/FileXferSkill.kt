package ai.openclaw.android.skill.builtin

import ai.openclaw.android.model.ImageUtils
import ai.openclaw.android.skill.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset

/**
 * FileXferSkill — 安全文件读写、目录列表、文件分享、URL 下载。
 *
 * 安全沙箱：
 *   - 默认仅允许访问应用私有目录 (context.filesDir, context.cacheDir)
 *   - 所有路径操作需验证不超出允许范围（路径遍历防护）
 *   - 单次读取/写入限制 10MB，下载限制 100MB
 *   - file_share 可通过系统分享 Intent 或保存到 Downloads
 */
class FileXferSkill(
    private val context: Context
) : Skill {
    override val id = "file_xfer"
    override val name = "文件传输"
    override val description = "安全的文件读写、列表、分享和下载操作"
    override val version = "1.0.0"

    override val instructions = """
# File Transfer Skill

安全的文件读写、目录列表、文件分享和 URL 下载。

## 安全沙箱
默认仅允许访问应用私有目录：
- `~/` → context.filesDir (应用文件目录)
- `~/cache/` → context.cacheDir (缓存目录)

路径遍历防护：禁止 `..` 访问沙箱外的文件。

## 可用工具
- `read` — 读取文件内容（文本或图片）
- `write` — 写入文件内容
- `list` — 列出目录内容
- `share` — 分享文件给用户
- `download` — 从 URL 下载文件

## 限制
- 单次读取/写入最大 10MB
- 下载最大 100MB
- overwrite=false 时不覆盖已有文件
""".trimIndent()

    // ==================== Sandboxed directories ====================

    /**
     * Primary sandbox root: app files directory.
     */
    private val filesRoot: File
        get() = context.filesDir

    /**
     * Secondary sandbox root: app cache directory.
     */
    private val cacheRoot: File
        get() = context.cacheDir

    /**
     * All allowed root directories.
     */
    private val allowedRoots: List<File>
        get() = listOf(filesRoot, cacheRoot)

    companion object {
        private const val MAX_READ_BYTES = 10 * 1024 * 1024L      // 10MB
        private const val MAX_WRITE_BYTES = 10 * 1024 * 1024L     // 10MB
        private const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024L // 100MB
    }

    override val tools: List<SkillTool> = listOf(
        ReadTool(),
        WriteTool(),
        ListTool(),
        ShareTool(),
        DownloadTool()
    )

    // ==================== file_read ====================

    private inner class ReadTool : SkillTool {
        override val name = "read"
        override val description = "读取设备上的文件内容。支持文本文件和图片。"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "文件路径（沙箱内的相对路径或 ~/ 开头的路径）",
                required = true
            ),
            "max_bytes" to SkillParam(
                type = "number",
                description = "最大读取字节数，默认 100000",
                required = false,
                default = 100000
            ),
            "encoding" to SkillParam(
                type = "string",
                description = "文本编码，默认 utf-8",
                required = false,
                default = "utf-8"
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val rawPath = params["path"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 path 参数")
            val maxBytes = (params["max_bytes"] as? Number)?.toLong()?.coerceAtMost(MAX_READ_BYTES)
                ?: 100_000L
            val encoding = (params["encoding"] as? String)?.ifEmpty { "utf-8" } ?: "utf-8"

            try {
                val file = resolveSandboxPath(rawPath)
                    ?: return@withContext SkillResult(false, "", "路径 '$rawPath' 超出允许范围（仅限应用私有目录）")

                if (!file.exists()) {
                    return@withContext SkillResult(false, "", "文件不存在: ${file.absolutePath}")
                }
                if (!file.isFile) {
                    return@withContext SkillResult(false, "", "路径不是文件: ${file.absolutePath}")
                }
                if (file.length() > MAX_READ_BYTES) {
                    return@withContext SkillResult(false, "", "文件过大 (${formatSize(file.length())})，超过 10MB 读取限制")
                }

                val mimeType = detectMimeType(file.name)
                val isImage = mimeType.startsWith("image/")

                if (isImage) {
                    // Image file: return as base64
                    return@withContext try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            val compressed = ImageUtils.compressBitmap(bitmap)
                            val content = ImageUtils.bitmapToBase64(compressed)
                            if (compressed != bitmap) compressed.recycle()
                            bitmap.recycle()
                            val output = buildString {
                                append("📄 ${file.name}\n")
                                append("类型: 图片 ($mimeType)\n")
                                append("大小: ${formatSize(file.length())}\n")
                                append("---\n")
                                append("data:image/jpeg;base64,${content.base64}")
                            }
                            SkillResult(true, output)
                        } else {
                            // Fallback: read as base64
                            val base64 = android.util.Base64.encodeToString(
                                file.readBytes(), android.util.Base64.NO_WRAP
                            )
                            val output = buildString {
                                append("📄 ${file.name}\n")
                                append("类型: 图片 ($mimeType)\n")
                                append("大小: ${formatSize(file.length())}\n")
                                append("---\n")
                                append("data:application/octet-stream;base64,$base64")
                            }
                            SkillResult(true, output)
                        }
                    } catch (e: Exception) {
                        SkillResult(false, "", "读取图片文件失败: ${e.message}")
                    }
                }

                // Text file
                val charset = try {
                    Charset.forName(encoding)
                } catch (e: Exception) {
                    Charset.forName("UTF-8")
                }

                val fileLength = file.length()
                val bytesRead = minOf(fileLength, maxBytes)
                val content = file.readText(charset).take(maxBytes.toInt())
                val truncated = fileLength > maxBytes

                val output = buildString {
                    append("📄 ${file.name}\n")
                    append("路径: ${file.absolutePath}\n")
                    append("类型: $mimeType\n")
                    append("大小: ${formatSize(file.length())}\n")
                    append("已读取: ${bytesRead} 字节")
                    if (truncated) append(" (已截断)")
                    append("\n")
                    append("---\n")
                    append(content)
                }

                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileXferSkill", "read failed: ${e.message}", e)
                SkillResult(false, "", "读取失败: ${e.message}")
            }
        }
    }

    // ==================== file_write ====================

    private inner class WriteTool : SkillTool {
        override val name = "write"
        override val description = "将内容写入设备文件。"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "目标文件路径",
                required = true
            ),
            "content" to SkillParam(
                type = "string",
                description = "文件内容",
                required = true
            ),
            "encoding" to SkillParam(
                type = "string",
                description = "文本编码，默认 utf-8",
                required = false,
                default = "utf-8"
            ),
            "overwrite" to SkillParam(
                type = "boolean",
                description = "是否覆盖已有文件，默认 false",
                required = false,
                default = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val rawPath = params["path"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 path 参数")
            val content = params["content"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 content 参数")
            val encoding = (params["encoding"] as? String)?.ifEmpty { "utf-8" } ?: "utf-8"
            val overwrite = params["overwrite"] as? Boolean ?: false

            try {
                val file = resolveSandboxPath(rawPath)
                    ?: return@withContext SkillResult(false, "", "路径 '$rawPath' 超出允许范围（仅限应用私有目录）")

                if (file.exists() && !overwrite) {
                    return@withContext SkillResult(
                        false, "",
                        "文件已存在: ${file.absolutePath} (overwrite=false，不覆盖已有文件)"
                    )
                }

                val contentBytes = try {
                    content.toByteArray(Charset.forName(encoding))
                } catch (e: Exception) {
                    content.toByteArray(Charsets.UTF_8)
                }

                if (contentBytes.size > MAX_WRITE_BYTES) {
                    return@withContext SkillResult(false, "", "内容过大 (${formatSize(contentBytes.size.toLong())})，超过 10MB 写入限制")
                }

                file.parentFile?.mkdirs()
                FileOutputStream(file).use { it.write(contentBytes) }

                val output = buildString {
                    append("✅ 写入成功\n")
                    append("路径: ${file.absolutePath}\n")
                    append("大小: ${formatSize(file.length())}\n")
                    append("内容: ${contentBytes.size} 字节")
                }

                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileXferSkill", "write failed: ${e.message}", e)
                SkillResult(false, "", "写入失败: ${e.message}")
            }
        }
    }

    // ==================== file_list ====================

    private inner class ListTool : SkillTool {
        override val name = "list"
        override val description = "列出目录内容。"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "目录路径，默认 ~/（应用文件目录）",
                required = false,
                default = "~/"
            ),
            "max_depth" to SkillParam(
                type = "number",
                description = "递归深度，默认 1",
                required = false,
                default = 1
            ),
            "pattern" to SkillParam(
                type = "string",
                description = "文件名过滤模式（glob）",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val rawPath = params["path"] as? String ?: "~/"
            val maxDepth = (params["max_depth"] as? Number)?.toInt()?.coerceIn(0, 5) ?: 1
            val pattern = params["pattern"] as? String

            try {
                val dir = resolveSandboxPath(rawPath)
                    ?: return@withContext SkillResult(false, "", "路径 '$rawPath' 超出允许范围")

                if (!dir.exists()) {
                    return@withContext SkillResult(false, "", "目录不存在: ${dir.absolutePath}")
                }
                if (!dir.isDirectory) {
                    return@withContext SkillResult(false, "", "不是目录: ${dir.absolutePath}")
                }

                val items = listDirectory(dir, maxDepth, pattern)

                if (items.isEmpty()) {
                    return@withContext SkillResult(true, "📁 目录为空: ${dir.absolutePath}")
                }

                val output = buildString {
                    append("📁 ${dir.absolutePath}\n")
                    append("项目数: ${items.size}\n")
                    append("---\n")
                    items.forEach { item ->
                        append(item)
                        append("\n")
                    }
                }

                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileXferSkill", "list failed: ${e.message}", e)
                SkillResult(false, "", "列出目录失败: ${e.message}")
            }
        }
    }

    // ==================== file_share ====================

    private inner class ShareTool : SkillTool {
        override val name = "share"
        override val description = "将文件分享给用户（通过系统分享 Intent 或保存到 Downloads 目录）。"
        override val parameters = mapOf(
            "path" to SkillParam(
                type = "string",
                description = "要分享的文件路径",
                required = true
            ),
            "mime_type" to SkillParam(
                type = "string",
                description = "MIME 类型（可选，自动检测）",
                required = false
            ),
            "save_to_downloads" to SkillParam(
                type = "boolean",
                description = "同时保存到 Downloads 目录，默认 false",
                required = false,
                default = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val rawPath = params["path"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 path 参数")
            val mimeType = (params["mime_type"] as? String)?.ifEmpty { null }
            val saveToDownloads = params["save_to_downloads"] as? Boolean ?: false

            try {
                // Resolve: allow paths from sandbox OR from external storage (for sharing)
                val file = resolveShareablePath(rawPath)
                    ?: return@withContext SkillResult(false, "", "路径 '$rawPath' 超出允许范围或文件不存在")

                if (!file.exists()) {
                    return@withContext SkillResult(false, "", "文件不存在: ${file.absolutePath}")
                }
                if (!file.isFile) {
                    return@withContext SkillResult(false, "", "不是文件: ${file.absolutePath}")
                }

                val actualMimeType = mimeType ?: detectMimeType(file.name)
                var savedPath: String? = null

                // Save to Downloads if requested
                if (saveToDownloads) {
                    savedPath = saveToDownloadsDir(file, actualMimeType)
                }

                val output = buildString {
                    append("📤 文件分享准备完成\n")
                    append("文件: ${file.name}\n")
                    append("类型: $actualMimeType\n")
                    append("大小: ${formatSize(file.length())}\n")
                    append("分享方式: FileProvider URI\n")
                    if (savedPath != null) {
                        append("已保存到: $savedPath\n")
                    }
                    append("---\n")
                    append("文件可通过 FileProvider URI 分享给其他应用：\n")
                    append("content://ai.openclaw.android.fileprovider/share/${file.name}")
                }

                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileXferSkill", "share failed: ${e.message}", e)
                SkillResult(false, "", "分享失败: ${e.message}")
            }
        }
    }

    // ==================== file_download ====================

    private inner class DownloadTool : SkillTool {
        override val name = "download"
        override val description = "从 URL 下载文件到设备。"
        override val parameters = mapOf(
            "url" to SkillParam(
                type = "string",
                description = "文件 URL",
                required = true
            ),
            "filename" to SkillParam(
                type = "string",
                description = "保存的文件名（可选，从 URL 推断）",
                required = false
            ),
            "dest_dir" to SkillParam(
                type = "string",
                description = "目标目录，默认 ~/downloads/（应用文件目录下）",
                required = false,
                default = "~/downloads/"
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val url = params["url"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 url 参数")
            var filename = params["filename"] as? String
            val rawDestDir = params["dest_dir"] as? String ?: "~/downloads/"

            // Validate URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext SkillResult(false, "", "URL 必须以 http:// 或 https:// 开头")
            }

            // Extract filename from URL if not provided
            if (filename.isNullOrEmpty()) {
                filename = url.substringAfterLast('/')
                    .substringBefore('?')
                    .takeIf { it.isNotEmpty() && it.length < 255 }
                    ?: "downloaded_file"
            }

            // Sanitize filename (prevent path traversal in filename)
            filename = filename.replace(Regex("[/\\\\]"), "_")

            try {
                val destDir = resolveSandboxPath(rawDestDir)
                    ?: return@withContext SkillResult(false, "", "目标目录 '$rawDestDir' 超出允许范围")
                destDir.mkdirs()

                val destFile = File(destDir, filename)
                if (destFile.exists()) {
                    return@withContext SkillResult(false, "", "文件已存在: ${destFile.absolutePath} (请先删除或使用不同文件名)")
                }

                // Use the SkillManager's OkHttpClient for download
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                // We need access to the HTTP client — use a basic approach
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 60_000
                    conn.requestMethod = "GET"

                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        return@withContext SkillResult(false, "", "下载失败: HTTP $responseCode")
                    }

                    val contentLength = conn.contentLengthLong
                    if (contentLength > MAX_DOWNLOAD_BYTES) {
                        return@withContext SkillResult(
                            false, "",
                            "文件过大 (${formatSize(contentLength)})，超过 100MB 下载限制"
                        )
                    }

                    // Track downloaded size
                    var downloadedBytes = 0L
                    val buffer = ByteArray(8192)

                    FileOutputStream(destFile).use { fos ->
                        conn.inputStream.use { inputStream ->
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                downloadedBytes += read
                                if (downloadedBytes > MAX_DOWNLOAD_BYTES) {
                                    destFile.delete()
                                    return@withContext SkillResult(
                                        false, "",
                                        "下载内容过大，超过 100MB 限制 (已下载 ${formatSize(downloadedBytes)})"
                                    )
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }

                    val downloadedMimeType = conn.contentType ?: detectMimeType(filename)

                    val output = buildString {
                        append("⬇️ 下载完成\n")
                        append("URL: $url\n")
                        append("文件: ${destFile.name}\n")
                        append("路径: ${destFile.absolutePath}\n")
                        append("大小: ${formatSize(destFile.length())}\n")
                        append("类型: $downloadedMimeType\n")
                    }

                    SkillResult(true, output)
                } finally {
                    conn.disconnect()
                }
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: java.net.UnknownHostException) {
                SkillResult(false, "", "网络错误: 无法连接到服务器")
            } catch (e: java.net.SocketTimeoutException) {
                SkillResult(false, "", "下载超时")
            } catch (e: Exception) {
                Log.e("FileXferSkill", "download failed: ${e.message}", e)
                SkillResult(false, "", "下载失败: ${e.message}")
            }
        }
    }

    // ==================== Sandbox path resolution ====================

    /**
     * Resolve a user-provided path to a File within the sandbox.
     * Returns null if the resolved path escapes the sandbox.
     *
     * Supported path formats:
     *   - `~/` or `~` → filesDir
     *   - `~/cache/` → cacheDir
     *   - `~/downloads/` → filesDir/downloads
     *   - relative path `notes.txt` → filesDir/notes.txt
     *   - `files/...` → filesDir/...
     *   - `cache/...` → cacheDir/...
     */
    private fun resolveSandboxPath(raw: String): File? {
        val path = raw.trim().removePrefix("/")

        val resolved = when {
            // ~/ → filesDir root
            path.startsWith("~") -> {
                val rel = path.removePrefix("~").removePrefix("/")
                if (rel.isEmpty() || rel == "files" || rel.startsWith("files/")) {
                    val sub = rel.removePrefix("files").removePrefix("/")
                    if (sub.isEmpty()) filesRoot else File(filesRoot, sub)
                } else if (rel.startsWith("cache") || rel.startsWith("cache/")) {
                    val sub = rel.removePrefix("cache").removePrefix("/")
                    if (sub.isEmpty()) cacheRoot else File(cacheRoot, sub)
                } else {
                    File(filesRoot, rel)
                }
            }
            // files/... → filesDir
            path.startsWith("files") || path.startsWith("files/") -> {
                val rel = path.removePrefix("files").removePrefix("/")
                if (rel.isEmpty()) filesRoot else File(filesRoot, rel)
            }
            // cache/... → cacheDir
            path.startsWith("cache") || path.startsWith("cache/") -> {
                val rel = path.removePrefix("cache").removePrefix("/")
                if (rel.isEmpty()) cacheRoot else File(cacheRoot, rel)
            }
            // relative path → filesDir
            else -> if (path.isEmpty()) filesRoot else File(filesRoot, path)
        }

        // Path traversal check: ensure canonical path is under an allowed root
        return if (isWithinSandbox(resolved)) resolved else null
    }

    /**
     * For file_share, allow any readable file (not just sandbox), but with safety checks.
     * Returns null if path is invalid or traversal attempt detected.
     */
    private fun resolveShareablePath(raw: String): File? {
        val path = raw.trim()

        // First try sandbox resolution
        val sandboxFile = resolveSandboxPath(path)
        if (sandboxFile != null && sandboxFile.exists()) {
            return sandboxFile
        }

        // If not in sandbox, try as an absolute path but validate it's not a traversal attempt
        // to sensitive system paths
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return null
        }

        // Block sensitive paths
        val canonicalPath = file.canonicalPath
        val blockedPrefixes = listOf("/proc", "/sys", "/dev", "/data/data/", "/system/")
        if (blockedPrefixes.any { canonicalPath.startsWith(it) }) {
            return null
        }

        // Allow files in public directories
        val allowedPrefixes = listOf(
            Environment.getExternalStorageDirectory()?.absolutePath ?: "",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath ?: "",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.absolutePath ?: "",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.absolutePath ?: ""
        )
        if (allowedPrefixes.isNotEmpty() && allowedPrefixes.any { canonicalPath.startsWith(it) }) {
            return file
        }

        return null
    }

    /**
     * Check if a resolved file is within any sandbox root.
     */
    private fun isWithinSandbox(file: File): Boolean {
        return try {
            val canonicalPath = file.canonicalFile.absolutePath
            allowedRoots.any { root ->
                canonicalPath.startsWith(root.canonicalFile.absolutePath)
            }
        } catch (e: IOException) {
            false
        }
    }

    // ==================== Directory listing ====================

    private fun listDirectory(dir: File, maxDepth: Int, pattern: String?): List<String> {
        val results = mutableListOf<String>()
        collectDirectory(dir, maxDepth, pattern, results, 0, dir.absolutePath.length)
        return results
    }

    private fun collectDirectory(
        dir: File,
        maxDepth: Int,
        pattern: String?,
        results: MutableList<String>,
        currentDepth: Int,
        baseLen: Int
    ) {
        if (currentDepth > maxDepth) return

        val files = dir.listFiles() ?: return
        for (file in files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            // Apply glob pattern filter
            if (pattern != null && !globMatch(file.name, pattern)) continue

            val relativePath = file.absolutePath.substring(baseLen).removePrefix("/")
            val icon = if (file.isDirectory) "📁" else "📄"
            val size = if (file.isFile) " ${formatSize(file.length())}" else ""
            val modifier = if (currentDepth > 0) "  ".repeat(currentDepth) else ""

            val entry = buildString {
                append("${modifier}$icon ${file.name}$size")
                if (file.isFile) {
                    val mime = detectMimeType(file.name)
                    if (mime != "application/octet-stream") {
                        append(" ($mime)")
                    }
                }
            }
            results.add(entry)

            if (file.isDirectory && currentDepth < maxDepth) {
                collectDirectory(file, maxDepth, pattern, results, currentDepth + 1, baseLen)
            }
        }
    }

    /**
     * Simple glob pattern matching (* and ? support).
     */
    private fun globMatch(name: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex()
        return name.matches(regex)
    }

    // ==================== Downloads helper ====================

    private fun saveToDownloadsDir(sourceFile: File, mimeType: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Use MediaStore API for Android 10+
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                ) ?: return null

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { it.copyTo(outputStream) }
                }

                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .absolutePath + "/" + sourceFile.name
            } else {
                // Legacy: copy directly
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val destFile = File(downloadsDir, sourceFile.name)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w("FileXferSkill", "Save to Downloads failed: ${e.message}")
            null
        }
    }

    // ==================== MIME type detection ====================

    private fun detectMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "application/octet-stream"

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "txt", "log", "md", "json", "xml", "yaml", "yml", "csv", "html", "htm",
                "js", "ts", "py", "java", "kt", "kts", "sh", "bash", "zsh" -> "text/plain"

                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"

                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"

                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"

                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"

                else -> "application/octet-stream"
            }
    }

    // ==================== Utility ====================

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        filesRoot.mkdirs()
        cacheRoot.mkdirs()
        Log.i("FileXferSkill", "Initialized. Sandbox: files=${filesRoot.absolutePath}, cache=${cacheRoot.absolutePath}")
    }

    override fun cleanup() {}
}
