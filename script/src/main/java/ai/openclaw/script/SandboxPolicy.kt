package ai.openclaw.script

import java.io.File

/**
 * 沙箱安全策略
 */
data class SandboxPolicy(
    val timeoutMs: Long = 10_000L,
    val maxMemoryMB: Int = 16,
    val sandboxDir: File,
    val allowNetwork: Boolean = true,
    val allowFileWrite: Boolean = true,
    val allowedDomains: List<String>? = null
)
