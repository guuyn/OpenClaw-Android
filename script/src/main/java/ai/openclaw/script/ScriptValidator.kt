package ai.openclaw.script

/**
 * 脚本静态校验器
 *
 * 在执行前检查脚本是否包含危险模式。
 */
object ScriptValidator {

    private const val MAX_SCRIPT_SIZE = 50 * 1024 // 50KB

    private val BLOCKED_PATTERNS = listOf(
        // 模块加载
        Regex("""\bimport\b""", RegexOption.IGNORE_CASE),
        Regex("""\brequire\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""\beval\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""\bnew\s+Function\b""", RegexOption.IGNORE_CASE),
        // 定时器
        Regex("""\bsetTimeout\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsetInterval\b""", RegexOption.IGNORE_CASE),
        // 原型污染
        Regex("""__proto__""", RegexOption.IGNORE_CASE),
        Regex("""\bconstructor\s*\[""", RegexOption.IGNORE_CASE),
        Regex("""\bconstructor\..""", RegexOption.IGNORE_CASE),
        // Java/Android 访问
        Regex("""\bjava\.""", RegexOption.IGNORE_CASE),
        Regex("""\bandroid\.""", RegexOption.IGNORE_CASE),
        Regex("""\bPackages\b""", RegexOption.IGNORE_CASE),
        // 系统对象
        Regex("""\bprocess\b""", RegexOption.IGNORE_CASE),
        Regex("""\bglobal\b""", RegexOption.IGNORE_CASE),
        Regex("""\bglobalThis\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwindow\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdocument\b""", RegexOption.IGNORE_CASE)
    )

    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null
    )

    fun validate(script: String): ValidationResult {
        if (script.isBlank()) {
            return ValidationResult(false, "脚本不能为空")
        }

        if (script.length > MAX_SCRIPT_SIZE) {
            return ValidationResult(false, "脚本过长（${script.length} > ${MAX_SCRIPT_SIZE}）")
        }

        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(script)) {
                return ValidationResult(false, "脚本包含禁止的模式: ${pattern.pattern}")
            }
        }

        return ValidationResult(true)
    }
}
