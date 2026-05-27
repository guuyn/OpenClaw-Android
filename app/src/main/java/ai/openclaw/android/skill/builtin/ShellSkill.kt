package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ShellSkill — 执行受限的 shell 命令。
 *
 * 安全策略：
 *   - 白名单：仅允许预定义的系统命令前缀
 *   - 黑名单：拦截危险模式（删除、写入、提权、重定向、管道等）
 *   - 超时：防止命令挂起（默认 10 秒）
 *   - 输出截断：stdout 最大 10000 字符
 *
 * 注意：Android 应用无 root 权限，只能执行非特权命令。
 */
class ShellSkill(
    private val context: Context
) : Skill {
    override val id = "shell"
    override val name = "Shell 执行"
    override val description = "执行受限的 shell 命令获取设备信息、系统状态等"
    override val version = "1.0.0"

    override val instructions = """
# Shell Skill

执行受限的 shell 命令。

## 安全限制
受 Android 安全模型约束，仅允许以下命令：
- 设备信息: getprop, dumpsys, pm list, pm path
- 系统状态: cat /proc/meminfo, cat /proc/cpuinfo, df, free, top, ps
- 网络: ip route, ip addr, netstat, ping
- 日志: logcat -d -t
- 存储: ls, find, du
- 其他: date, whoami, id, uname, uptime

危险命令（rm, dd, mkfs, su, reboot 等）被严格禁止。

## 可用工具
- `exec` — 执行 shell 命令并返回输出
""".trimIndent()

    override val tools: List<SkillTool> = listOf(
        ExecTool()
    )

    // ==================== shell_exec ====================

    private inner class ExecTool : SkillTool {
        override val name = "exec"
        override val description =
            "执行 shell 命令并返回输出。注意：受 Android 安全模型限制，只能执行非特权命令。"
        override val parameters = mapOf(
            "command" to SkillParam(
                type = "string",
                description = "要执行的命令",
                required = true
            ),
            "timeout_seconds" to SkillParam(
                type = "number",
                description = "命令超时时间（秒），默认 10",
                required = false,
                default = 10
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val command = params["command"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 command 参数")
            val timeoutSeconds = (params["timeout_seconds"] as? Number)?.toInt()?.coerceIn(1, 30) ?: 10

            // Security validation
            val validation = validateCommand(command)
            if (!validation.allowed) {
                Log.w("ShellSkill", "Command blocked: $command — ${validation.reason}")
                return@withContext SkillResult(false, "", "命令被安全策略拒绝: ${validation.reason}")
            }

            Log.i("ShellSkill", "Executing: $command (timeout=${timeoutSeconds}s)")

            try {
                executeShellCommand(command, timeoutSeconds)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("ShellSkill", "Execution failed: ${e.message}", e)
                SkillResult(false, "", "命令执行失败: ${e.message}")
            }
        }
    }

    // ==================== Command security validation ====================

    /**
     * Command whitelist: base commands that are allowed.
     * Each entry is (baseCommand, arrayOf allowed argument prefixes).
     * If allowed prefixes is empty, only the base command with no args is allowed.
     */
    private data class AllowedCommand(
        val baseCommand: String,
        val allowedPrefixes: Array<String> = emptyArray()
    )

    private val whitelist = listOf(
        AllowedCommand("getprop"),
        AllowedCommand("dumpsys"),
        AllowedCommand("pm", arrayOf("list", "path")),
        AllowedCommand("cat", arrayOf("/proc/meminfo", "/proc/cpuinfo", "/proc/loadavg")),
        AllowedCommand("df"),
        AllowedCommand("free"),
        AllowedCommand("top", arrayOf("-n")),
        AllowedCommand("ps"),
        AllowedCommand("ip", arrayOf("route", "addr", "link", "neigh", "-4", "-6")),
        AllowedCommand("netstat"),
        AllowedCommand("ping"),
        AllowedCommand("logcat", arrayOf("-d", "-t")),
        AllowedCommand("ls", arrayOf("-l", "-la", "-a", "-l", "-lh")),
        AllowedCommand("find"),
        AllowedCommand("du"),
        AllowedCommand("date"),
        AllowedCommand("whoami"),
        AllowedCommand("id"),
        AllowedCommand("uname"),
        AllowedCommand("uptime"),
        AllowedCommand("wc"),
        AllowedCommand("head"),
        AllowedCommand("tail"),
        AllowedCommand("stat"),
        AllowedCommand("getenforce"),
    )

    /**
     * Blacklist patterns — dangerous operations that must always be blocked.
     * These are checked even if the command matches the whitelist.
     */
    private val blacklistPatterns = listOf(
        "rm -rf", "rm -r ", "rm --recursive",
        "dd if=", "dd of=", "dd bs=",
        "mkfs", "mke2fs", "mkswap",
        "format",
        "chmod 777", "chmod -R",
        "chown root", "chown system",
        "su ", "sudo ", "su -",
        "reboot", "shutdown", "poweroff", "halt",
        "kill -9", "kill -SIGKILL", "kill -KILL",
        "am start", "am broadcast", "am force-stop",
        "settings put",
        "setprop ", "input ",
        "mount ", "umount ",
        "iptables",
        "/dev/", "/sys/kernel/", "/proc/sysrq-trigger",
    )

    /** Patterns that allow shell meta-character injection */
    private val injectionPatterns = listOf(
        Regex(";\\s*$"),        // trailing semicolon (command separator)
        Regex("\\|"),            // pipe
        Regex("&\\s*$"),         // trailing ampersand (background)
        Regex("&&"),             // logical AND
        Regex("\\|\\|"),         // logical OR
        Regex("\\$\\("),         // command substitution $(...)
        Regex("`"),              // backtick substitution
        Regex(">\\s*/"),         // redirect to absolute path
        Regex(">>"),             // append redirect
        Regex("<\\s*/"),         // input redirect from absolute path
    )

    private data class ValidationResult(val allowed: Boolean, val reason: String = "")

    /**
     * Validate a command against both whitelist and blacklist.
     */
    private fun validateCommand(command: String): ValidationResult {
        val trimmed = command.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult(false, "命令不能为空")
        }

        // Step 1: Check blacklist
        val lowerCommand = trimmed.lowercase()
        for (pattern in blacklistPatterns) {
            if (lowerCommand.contains(pattern.lowercase())) {
                return ValidationResult(false, "包含被禁止的命令模式: $pattern")
            }
        }

        // Step 2: Check injection patterns
        for (pattern in injectionPatterns) {
            if (pattern.containsMatchIn(trimmed)) {
                return ValidationResult(false, "包含不允许的 shell 语法（重定向/管道/命令注入）")
            }
        }

        // Step 3: Check whitelist
        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return ValidationResult(false, "命令不能为空")
        }

        val baseCommand = parts[0]

        for (entry in whitelist) {
            if (baseCommand != entry.baseCommand) continue

            // Base command matched
            if (entry.allowedPrefixes.isEmpty()) {
                // Only base command with no args allowed
                return if (parts.size == 1) {
                    ValidationResult(true)
                } else {
                    ValidationResult(false, "命令 '$baseCommand' 不允许带参数")
                }
            }

            // Check if arguments match allowed prefixes
            if (parts.size == 1) {
                return ValidationResult(true)
            }

            val argsStr = parts.drop(1).joinToString(" ")
            for (prefix in entry.allowedPrefixes) {
                if (argsStr.startsWith(prefix) || argsStr.contains(" $prefix")) {
                    return ValidationResult(true)
                }
            }

            // Args don't match any allowed prefix
            return ValidationResult(false, "命令 '$trimmed' 的参数不在允许范围内")
        }

        return ValidationResult(false, "命令 '$baseCommand' 不在白名单中")
    }

    // ==================== Shell execution ====================

    private fun executeShellCommand(command: String, timeoutSeconds: Int): SkillResult {
        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder("sh", "-c", command)
        processBuilder.redirectErrorStream(false)

        val process = processBuilder.start()

        // Read stdout and stderr on separate threads to avoid deadlock
        var stdout = ""
        var stderr = ""

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        val stdoutThread = Thread {
            stdout = stdoutReader.readText()
        }
        val stderrThread = Thread {
            stderr = stderrReader.readText()
        }

        stdoutThread.start()
        stderrThread.start()

        val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            val elapsed = System.currentTimeMillis() - startTime
            return SkillResult(
                false,
                "",
                "命令执行超时 (${timeoutSeconds}s)，已强制终止"
            )
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        stdoutReader.close()
        stderrReader.close()

        val exitCode = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            -1
        }

        val duration = System.currentTimeMillis() - startTime

        // Truncate output
        val truncatedStdout = if (stdout.length > 10000) {
            stdout.take(10000) + "\n... (输出已截断，超过 10000 字符)"
        } else {
            stdout
        }

        val truncatedStderr = if (stderr.length > 10000) {
            stderr.take(10000) + "\n... (输出已截断，超过 10000 字符)"
        } else {
            stderr
        }

        val output = buildJsonResult(
            success = exitCode == 0,
            stdout = truncatedStdout,
            stderr = truncatedStderr,
            exitCode = exitCode,
            durationMs = duration
        )

        return SkillResult(
            success = exitCode == 0,
            output = output,
            error = if (exitCode != 0) "命令退出码: $exitCode" else null
        )
    }

    private fun buildJsonResult(
        success: Boolean,
        stdout: String,
        stderr: String,
        exitCode: Int,
        durationMs: Long
    ): String {
        return buildString {
            append("{")
            append("\"success\":$success,")
            append("\"stdout\":${escapeJsonString(stdout)},")
            append("\"stderr\":${escapeJsonString(stderr)},")
            append("\"exit_code\":$exitCode,")
            append("\"duration_ms\":$durationMs")
            append("}")
        }
    }

    private fun escapeJsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        Log.i("ShellSkill", "Initialized with ${whitelist.size} allowed commands")
    }

    override fun cleanup() {}
}
