package ai.openclaw.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== A2UI 卡片类型路由 ====================

/**
 * 根据 A2UI 卡片类型渲染对应风格的卡片
 * 新版本：接收 A2UICard 对象
 */
@Composable
fun A2UICardRouter(
    card: A2UICard,
    modifier: Modifier = Modifier
) {
    val data = card.rawData.mapValues { it.value?.toString() ?: "" }
    when (card.type) {
        "weather" -> WeatherCard(data, modifier)
        "location" -> LocationCard(data, modifier)
        "search" -> SearchCard(data, modifier)
        "translation" -> TranslationCard(data, modifier)
        "reminder" -> ReminderCard(data, modifier)
        else -> GenericA2UICard(card.type, data, modifier)
    }
}

/**
 * 旧版本路由（向后兼容）
 */
@Composable
fun A2UICardRouterLegacy(
    type: String,
    data: Map<String, String>,
    modifier: Modifier = Modifier
) {
    when (type) {
        "weather" -> WeatherCard(data, modifier)
        "location" -> LocationCard(data, modifier)
        "search" -> SearchCard(data, modifier)
        "translation" -> TranslationCard(data, modifier)
        "reminder" -> ReminderCard(data, modifier)
        else -> GenericA2UICard(type, data, modifier)
    }
}

// ==================== 天气卡片 ====================

@Composable
fun WeatherCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：天气图标 + 温度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 天气图标
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = getWeatherEmoji(data["condition"] ?: ""),
                            fontSize = 28.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 温度
                Column {
                    Text(
                        text = data["temperature"] ?: "--\u00B0",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = data["condition"] ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 城市和日期
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = data["location"] ?: data["city"] ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (data.containsKey("date")) {
                        Text(
                            text = data["date"] ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // 额外信息行
            val extraInfo = buildList {
                data["humidity"]?.let { add("湿度 $it") }
                data["wind"]?.let { add("风速 $it") }
                data["uv"]?.let { add("紫外线 $it") }
            }
            if (extraInfo.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    extraInfo.forEach { info ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = info,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 未来预报（水平滚动）
            val forecastDays = buildForecastList(data)
            if (forecastDays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    forecastDays.forEach { (day, temp, emoji) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(52.dp)
                        ) {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            Text(text = emoji, fontSize = 18.sp)
                            Text(
                                text = temp,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getWeatherEmoji(condition: String): String = when {
    condition.contains("晴") -> "\u2600\uFE0F"
    condition.contains("云") || condition.contains("阴") -> "\u2601\uFE0F"
    condition.contains("雨") -> "\uD83C\uDF27\uFE0F"
    condition.contains("雪") -> "\u2744\uFE0F"
    condition.contains("雷") -> "\u26C8\uFE0F"
    condition.contains("雾") || condition.contains("霾") -> "\uD83C\uDF2B\uFE0F"
    else -> "\uD83C\uDF24\uFE0F"
}

private fun buildForecastList(data: Map<String, String>): List<Triple<String, String, String>> {
    val result = mutableListOf<Triple<String, String, String>>()
    for (i in 1..5) {
        val day = data["day${i}"] ?: continue
        val temp = data["day${i}_temp"] ?: data["temp${i}"] ?: continue
        val cond = data["day${i}_condition"] ?: data["condition${i}"] ?: ""
        result.add(Triple(day, temp, getConditionEmoji(cond)))
    }
    return result
}

private fun getConditionEmoji(condition: String): String = when {
    condition.contains("晴") -> "\u2600\uFE0F"
    condition.contains("云") || condition.contains("阴") -> "\u2601\uFE0F"
    condition.contains("雨") -> "\uD83C\uDF27\uFE0F"
    condition.contains("雪") -> "\u2744\uFE0F"
    else -> "\uD83C\uDF24\uFE0F"
}

// ==================== 位置卡片 ====================

@Composable
fun LocationCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "位置",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = data["name"] ?: data["address"] ?: "位置信息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (data.containsKey("address") && data.containsKey("name")) {
                        Text(
                            text = data["address"] ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 详细信息
            Spacer(modifier = Modifier.height(12.dp))
            data.filterKeys { it !in setOf("name", "address", "type") }.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "$key: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

// ==================== 搜索卡片 ====================

@Composable
fun SearchCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：搜索图标 + 查询词
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = data["query"] ?: data["keyword"] ?: "搜索结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            // 搜索结果列表
            val results = buildSearchResults(data)
            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                results.forEachIndexed { index, (title, snippet) ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    if (snippet.isNotEmpty()) {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            maxLines = 2
                        )
                    }
                }
            } else {
                // 通用数据展示
                Spacer(modifier = Modifier.height(8.dp))
                data.filterKeys { it !in setOf("query", "keyword", "type") }.forEach { (key, value) ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = "$key: ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

private fun buildSearchResults(data: Map<String, String>): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    for (i in 1..5) {
        val title = data["result${i}"] ?: data["title${i}"] ?: continue
        val snippet = data["snippet${i}"] ?: data["desc${i}"] ?: ""
        results.add(title to snippet)
    }
    return results
}

// ==================== 翻译卡片 ====================

@Composable
fun TranslationCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "翻译",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                val langPair = buildString {
                    append(data["source_lang"] ?: data["from"] ?: "")
                    if (contains(data["source_lang"] ?: data["from"] ?: "") && contains(data["target_lang"] ?: data["to"] ?: "")) {
                        append(" → ")
                    }
                    append(data["target_lang"] ?: data["to"] ?: "")
                }
                if (langPair.isNotBlank()) {
                    Text(
                        text = langPair,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 原文
            val originalText = data["original"] ?: data["source"] ?: data["text"] ?: ""
            if (originalText.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = originalText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 翻译结果
            val translatedText = data["translated"] ?: data["result"] ?: data["translation"] ?: ""
            if (translatedText.isNotEmpty()) {
                if (originalText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = translatedText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ==================== 提醒卡片 ====================

@Composable
fun ReminderCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时钟/日历图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "\u23F0",
                        fontSize = 24.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 提醒标题
                Text(
                    text = data["title"] ?: data["text"] ?: "提醒",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 时间（突出显示）
                val time = data["time"] ?: data["date"] ?: data["datetime"] ?: ""
                if (time.isNotEmpty()) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 额外信息
                val note = data["note"] ?: data["description"] ?: ""
                if (note.isNotEmpty()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ==================== 通用 A2UI 卡片 ====================

@Composable
fun GenericA2UICard(type: String, data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 类型标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = type,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = type.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 数据键值对
            data.forEach { (key, value) ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "$key: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
