package ai.openclaw.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== Markdown 解析模型 ====================

/** Markdown 块级元素 */
sealed class MarkdownBlock {
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class QuoteBlock(val spans: List<MarkdownSpan>) : MarkdownBlock()
    data class OrderedList(val items: List<List<MarkdownSpan>>, val startIndex: Int = 1) : MarkdownBlock()
    data class UnorderedList(val items: List<List<MarkdownSpan>>) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

/** Markdown 行内元素 */
sealed class MarkdownSpan {
    data class Plain(val text: String) : MarkdownSpan()
    data class Bold(val text: String) : MarkdownSpan()
    data class Italic(val text: String) : MarkdownSpan()
    data class BoldItalic(val text: String) : MarkdownSpan()
    data class Code(val text: String) : MarkdownSpan()
    data class Link(val text: String, val url: String) : MarkdownSpan()
    data class Strikethrough(val text: String) : MarkdownSpan()
}

// ==================== Markdown 解析器 ====================

/** 将 Markdown 文本解析为块级元素列表 */
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 空行跳过
        if (line.isBlank()) {
            i++
            continue
        }

        // 代码块
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++ // 跳过结束的 ```
            continue
        }

        // 分割线
        if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // 引用块
        if (line.trimStart().startsWith("> ")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith("> ")) {
                quoteLines.add(lines[i].trimStart().removePrefix("> ").removePrefix(">"))
                i++
            }
            blocks.add(MarkdownBlock.QuoteBlock(parseInlineSpans(quoteLines.joinToString(" "))))
            continue
        }

        // 无序列表
        if (line.trimStart().matches(Regex("^[*+-] .+"))) {
            val items = mutableListOf<List<MarkdownSpan>>()
            while (i < lines.size && lines[i].trimStart().matches(Regex("^[*+-] .+"))) {
                val itemText = lines[i].trimStart().removePrefix("- ").removePrefix("* ").removePrefix("+ ")
                items.add(parseInlineSpans(itemText))
                i++
            }
            blocks.add(MarkdownBlock.UnorderedList(items))
            continue
        }

        // 有序列表
        if (line.trimStart().matches(Regex("^\\d+\\. .+"))) {
            val items = mutableListOf<List<MarkdownSpan>>()
            var startIdx = 1
            val startMatch = Regex("^(\\d+)\\.").find(line.trimStart())
            if (startMatch != null) startIdx = startMatch.groupValues[1].toInt()
            while (i < lines.size && lines[i].trimStart().matches(Regex("^\\d+\\. .+"))) {
                val itemText = lines[i].trimStart().replace(Regex("^\\d+\\. "), "")
                items.add(parseInlineSpans(itemText))
                i++
            }
            blocks.add(MarkdownBlock.OrderedList(items, startIdx))
            continue
        }

        // 普通段落（合并连续非空行）
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !lines[i].trimStart().startsWith("> ") &&
            !lines[i].trimStart().matches(Regex("^[*+-] .+")) &&
            !lines[i].trimStart().matches(Regex("^\\d+\\. .+")) &&
            !lines[i].trim().matches(Regex("^[-*_]{3,}$"))
        ) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(parseInlineSpans(paraLines.joinToString(" "))))
        }
    }

    return blocks
}

/** 解析行内 Markdown 样式 */
fun parseInlineSpans(text: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    // 匹配顺序：粗斜体 > 粗体 > 斜体 > 行内代码 > 链接 > 删除线
    val pattern = Regex(
        """\*\*\*(.+?)\*\*\*""" +    // 粗斜体
        """|\*\*(.+?)\*\*""" +        // 粗体
        """|\*(.+?)\*""" +            // 斜体
        """|`(.+?)`""" +              // 行内代码
        """|\[([^\]]+)\]\(([^)]+)\)""" + // 链接
        """|~~(.+?)~~""" +            // 删除线
        """|([^`*\[~]+|[`*\[~])"""    // 普通文本
    )

    var lastEnd = 0
    for (match in pattern.findAll(text)) {
        // 匹配前的普通文本
        if (match.range.first > lastEnd) {
            spans.add(MarkdownSpan.Plain(text.substring(lastEnd, match.range.first)))
        }

        when {
            match.groupValues[1].isNotEmpty() -> spans.add(MarkdownSpan.BoldItalic(match.groupValues[1]))
            match.groupValues[2].isNotEmpty() -> spans.add(MarkdownSpan.Bold(match.groupValues[2]))
            match.groupValues[3].isNotEmpty() -> spans.add(MarkdownSpan.Italic(match.groupValues[3]))
            match.groupValues[4].isNotEmpty() -> spans.add(MarkdownSpan.Code(match.groupValues[4]))
            match.groupValues[5].isNotEmpty() -> spans.add(MarkdownSpan.Link(match.groupValues[5], match.groupValues[6]))
            match.groupValues[7].isNotEmpty() -> spans.add(MarkdownSpan.Strikethrough(match.groupValues[7]))
            match.groupValues[8].isNotEmpty() -> spans.add(MarkdownSpan.Plain(match.groupValues[8]))
        }
        lastEnd = match.range.last + 1
    }

    // 剩余文本
    if (lastEnd < text.length) {
        spans.add(MarkdownSpan.Plain(text.substring(lastEnd)))
    }

    // 如果没有解析出任何内容，返回原文
    if (spans.isEmpty()) {
        spans.add(MarkdownSpan.Plain(text))
    }

    return spans
}

// ==================== AnnotatedString 构建 ====================

/** 将行内样式列表转为 AnnotatedString */
fun buildMarkdownAnnotatedString(
    spans: List<MarkdownSpan>,
    color: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color = color
): AnnotatedString {
    return buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is MarkdownSpan.Plain -> append(span.text)
                is MarkdownSpan.Bold -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(span.text)
                    pop()
                }
                is MarkdownSpan.Italic -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(span.text)
                    pop()
                }
                is MarkdownSpan.BoldItalic -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(span.text)
                    pop()
                }
                is MarkdownSpan.Code -> {
                    pushStyle(SpanStyle(
                        background = color.copy(alpha = 0.12f),
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ))
                    append(" ${span.text} ")
                    pop()
                }
                is MarkdownSpan.Link -> {
                    pushStyle(SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    ))
                    append(span.text)
                    pop()
                }
                is MarkdownSpan.Strikethrough -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(span.text)
                    pop()
                }
            }
        }
    }
}

// ==================== Markdown 渲染 Composable ====================

@Composable
fun MarkdownText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    isUser: Boolean = false
) {
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildMarkdownAnnotatedString(block.spans, color, linkColor = MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockView(block)
                }
                is MarkdownBlock.QuoteBlock -> {
                    QuoteBlockView(block, color)
                }
                is MarkdownBlock.OrderedList -> {
                    OrderedListView(block, color)
                }
                is MarkdownBlock.UnorderedList -> {
                    UnorderedListView(block, color)
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalRuleView()
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (block.language.isNotEmpty()) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QuoteBlockView(block: MarkdownBlock.QuoteBlock, color: androidx.compose.ui.graphics.Color) {
    val accentColor = MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 20.dp)
                .drawBehind {
                    drawRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = size
                    )
                }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildMarkdownAnnotatedString(block.spans, color, linkColor = accentColor),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = color.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun OrderedListView(block: MarkdownBlock.OrderedList, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { index, spans ->
            Row {
                Text(
                    text = "${block.startIndex + index}. ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = buildMarkdownAnnotatedString(spans, color, linkColor = MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun UnorderedListView(block: MarkdownBlock.UnorderedList, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEach { spans ->
            Row {
                Text(
                    text = "\u2022 ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = buildMarkdownAnnotatedString(spans, color, linkColor = MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun HorizontalRuleView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
    )
}
