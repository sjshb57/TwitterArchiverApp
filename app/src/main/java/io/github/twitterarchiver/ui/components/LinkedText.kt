package io.github.twitterarchiver.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** 匹配正文里的网址；结尾的中英文标点不算 URL 的一部分 */
private val URL_REGEX = Regex("""https?://[^\s<>"'）)】\]，。、！？；]+""")

/**
 * 显示用的短形式：去掉协议头，过长时截断。
 * 正文里存的是完整长链，直接显示很占地方；显示简短版、点击仍跳转完整地址，
 * 与网页端 display_url 风格一致。
 */
private fun displayForm(url: String, max: Int = 42): String {
    val bare = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    return if (bare.length <= max) bare else bare.take(max - 1) + "…"
}

/**
 * 带可点链接的正文。
 *
 * 正文是纯文本，URL 用 Text 渲染只是普通文字，既不高亮也点不动。
 * 这里用 LinkAnnotation.Url 把 URL 标成真正的链接，
 * 点击与无障碍由 Compose 处理，跳转交给系统浏览器。
 */
@Composable
fun LinkedText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated: AnnotatedString = remember(text, linkColor) {
        val styles = TextLinkStyles(
            style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)
        )
        buildAnnotatedString {
            var last = 0
            for (m in URL_REGEX.findAll(text)) {
                append(text.substring(last, m.range.first))
                val url = m.value
                withLink(LinkAnnotation.Url(url, styles)) { append(displayForm(url)) }
                last = m.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }

    Text(
        text = annotated,
        fontSize = fontSize,
        color = color,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}
