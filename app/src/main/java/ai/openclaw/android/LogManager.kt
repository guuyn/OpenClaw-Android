package ai.openclaw.android

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * LogManager - Centralized log management for OpenClaw Android
 * 
 * Singleton that collects service logs and provides real-time updates
 * via StateFlow for UI consumption.
 */
class LogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val maxLogs = 100
    
    fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > maxLogs) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
        
        // Also write to Android Logcat
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "INFO" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
    
    companion object {
        private val _shared = LogManager()
        val shared: LogManager = _shared
    }
}

/**
 * Represents a single log entry with timestamp, level, tag, and message
 */
data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)