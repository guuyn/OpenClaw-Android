package ai.openclaw.script

/**
 * 脚本执行结果
 */
data class ScriptResult(
    val success: Boolean,
    val output: String,
    val error: String?,
    val executionTimeMs: Long
) {
    companion object {
        fun success(output: String, executionTimeMs: Long = 0) =
            ScriptResult(true, output, null, executionTimeMs)

        fun failure(error: String, executionTimeMs: Long = 0) =
            ScriptResult(false, "", error, executionTimeMs)
    }
}
