package ai.openclaw.android.security

import ai.openclaw.android.LogManager
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Security audit logger for memory system operations.
 *
 * Records store, delete, merge, and sync events with tamper-evidence:
 * each entry includes a SHA-256 chain hash linking to the previous entry.
 * The hash chain makes it detectable if entries are removed or altered.
 */
object AuditLogger {

    private const val TAG = "AuditLogger"
    private const val MAX_ENTRIES = 500

    data class AuditEntry(
        val timestamp: Long,
        val operation: String,
        val targetId: Long,
        val detail: String,
        val previousHash: String
    ) {
        /** Compute this entry's hash: SHA-256(timestamp|operation|targetId|detail|previousHash) */
        val hash: String by lazy {
            val raw = "$timestamp|$operation|$targetId|$detail|$previousHash"
            sha256(raw)
        }
    }

    private val entries = mutableListOf<AuditEntry>()
    private var chainHead: String = "GENESIS"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** Record an audit event. Thread-safe via synchronized. */
    @Synchronized
    fun log(operation: String, targetId: Long, detail: String) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            targetId = targetId,
            detail = detail.take(200),
            previousHash = chainHead
        )
        chainHead = entry.hash
        entries.add(entry)

        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }

        LogManager.shared.log("INFO", TAG,
            "$operation id=$targetId ${detail.take(60)}")
    }

    /** Get all audit entries (snapshot). */
    @Synchronized
    fun getEntries(): List<AuditEntry> = entries.toList()

    /**
     * Verify the hash chain integrity.
     * @return true if the chain is intact, false if tampered.
     */
    @Synchronized
    fun verifyChain(): Boolean {
        var prev = "GENESIS"
        for (entry in entries) {
            if (entry.previousHash != prev) return false
            if (entry.hash != sha256("${entry.timestamp}|${entry.operation}|${entry.targetId}|${entry.detail}|${entry.previousHash}")) return false
            prev = entry.hash
        }
        return true
    }

    /** Export audit log as human-readable text. */
    fun export(): String {
        val sb = StringBuilder()
        sb.appendLine("=== OpenClaw Audit Log ===")
        sb.appendLine("Chain valid: ${verifyChain()}")
        sb.appendLine("Entries: ${entries.size}")
        sb.appendLine()
        for (entry in getEntries()) {
            sb.appendLine("[${dateFormat.format(Date(entry.timestamp))}] " +
                "${entry.operation} id=${entry.targetId} ${entry.detail}")
        }
        return sb.toString()
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
