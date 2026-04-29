package org.a2ui.compose.data

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.a2ui.compose.validation.PathValidator

class DataModelState {
    private val _data = mutableStateMapOf<String, Any?>()
    val data: SnapshotStateMap<String, Any?> = _data

    companion object {
        const val MAX_ENTRIES = 10_000
    }

    fun updateDataModel(path: String, value: Any?) {
        // ✅ 验证路径安全性
        PathValidator.validatePathOrThrow(path)

        // ✅ 检查条目数上限
        if (_data.size >= MAX_ENTRIES && path != "/") {
            val keys = path.removePrefix("/").split("/")
            if (!_data.containsKey(keys.first())) {
                throw IllegalStateException("Data model entry limit ($MAX_ENTRIES) exceeded")
            }
        }

        if (path == "/") {
            _data.clear()
            if (value is Map<*, *>) {
                value.entries.forEach { (k, v) ->
                    val key = k.toString()
                    // ✅ 验证每个键名
                    if (PathValidator.isValidPath("/$key")) {
                        _data[key] = v
                    }
                }
            }
        } else {
            val keys = path.removePrefix("/").split("/")
            updateNestedValue(_data, keys, 0, value)
        }
    }

    private fun updateNestedValue(map: SnapshotStateMap<String, Any?>, keys: List<String>, index: Int, value: Any?) {
        if (index == keys.size - 1) {
            if (value == null) {
                map.remove(keys[index])
            } else {
                map[keys[index]] = value
            }
        } else {
            val key = keys[index]
            val nextMap = map[key] as? SnapshotStateMap<String, Any?> ?: mutableStateMapOf()
            map[key] = nextMap
            updateNestedValue(nextMap, keys, index + 1, value)
        }
    }

    fun getValue(path: String): Any? {
        // ✅ 验证路径安全性，无效路径返回 null
        if (!PathValidator.isValidPath(path)) {
            return null
        }

        if (path == "/") {
            return _data
        }

        val keys = path.removePrefix("/").split("/")
        return getNestedValue(_data, keys, 0)
    }

    private fun getNestedValue(map: Map<String, Any?>, keys: List<String>, index: Int): Any? {
        if (index == keys.size) {
            return map
        }

        val key = keys[index]
        val value = map[key]

        // 先处理 Map/List 嵌套，再处理终端返回
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return getNestedValue(value as Map<String, Any?>, keys, index + 1)
        }

        // ✅ 支持 List 索引（如 /forecast/0/date）
        // 当前 value 是 List，下一个 key 应该是索引
        if (value is List<*>) {
            // 用下一个 key 作为索引
            val nextIndex = index + 1
            if (nextIndex < keys.size) {
                val idxKey = keys[nextIndex]
                val idx = idxKey.toIntOrNull()
                if (idx != null && idx in value.indices) {
                    val item = value[idx]
                    // 还有更多 key 要处理
                    if (nextIndex + 1 < keys.size) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            return getNestedValue(item as Map<String, Any?>, keys, nextIndex + 1)
                        }
                    } else {
                        // 索引是最后一个 key，返回 List 元素
                        return item
                    }
                }
                // 索引无效，返回 null
                return null
            }
            // 没有下一个 key 作为索引，如果当前是最后一个 key，返回整个 List
            if (index == keys.size - 1) {
                return value
            }
            // 还有更多 key 但 value 是 List 且无法索引，返回 null
            return null
        }

        // 终端值：最后一个 key 或无法继续遍历
        if (index == keys.size - 1) {
            return value
        }

        return null
    }

    fun clear() {
        _data.clear()
    }

    fun getDataSnapshot(): Map<String, Any?> {
        return _data.toMap()
    }
}
