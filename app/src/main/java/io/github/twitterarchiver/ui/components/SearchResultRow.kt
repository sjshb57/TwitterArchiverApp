package io.github.twitterarchiver.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 搜索命中条：只显示定位信息 + 三行正文摘要，点击才跳到完整推文。
 *
 * 搜索的目的是"找到某一条"，把每条命中都渲染成完整推文卡的话，
 * 搜一个常见字会出来几千张大卡片，只能一条条翻。
 */
@Composable
fun SearchResultRow(
    date: String,
    time: String,
    text: String,
    keyword: String,
    modifier: Modifier = Modifier,
    /** 全站搜索要显示是谁发的；单账号 feed 传 null */
    author: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        author?.let {
            it()
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(date, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Text(time, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        val accent = MaterialTheme.colorScheme.primary
        val highlighted = remember(text, keyword, accent) { highlight(text, keyword, accent) }
        Text(
            highlighted,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}

/** 把命中的关键词标出来。大小写不敏感，逐段拼接，不用正则以免关键词里的特殊字符出错。 */
private fun highlight(text: String, keyword: String, accent: Color): AnnotatedString {
    if (keyword.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val hit = text.indexOf(keyword, from, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(from))
                break
            }
            append(text.substring(from, hit))
            withStyle(SpanStyle(
                color = accent,
                background = accent.copy(alpha = 0.18f),
                fontWeight = FontWeight.SemiBold
            )) {
                append(text.substring(hit, hit + keyword.length))
            }
            from = hit + keyword.length
        }
    }
}
