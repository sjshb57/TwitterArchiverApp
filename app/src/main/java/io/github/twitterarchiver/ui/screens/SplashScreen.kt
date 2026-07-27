package io.github.twitterarchiver.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.ui.theme.Accent
import kotlinx.coroutines.delay

/**
 * 启动页：纪念语句逐行淡入 + 整体缓缓上移的滚动动效。
 * 不照搬 home，精简、有节奏感，时间较短。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    val scrollY = remember { Animatable(40f) }

    LaunchedEffect(Unit) {
        scrollY.animateTo(0f, tween(1600, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        for (i in 1..5) {
            stage = i
            delay(280)
        }
        delay(500)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer { translationY = scrollY.value * density }
        ) {
            // Logo
            Row(modifier = Modifier.alpha(if (stage >= 1) 1f else 0f)) {
                Text("Twitter", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Archiver", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
            }

            Spacer(Modifier.height(36.dp))

            Text(
                "互联网是现实的避难所。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                modifier = Modifier.alpha(if (stage >= 2) 1f else 0f)
            )
            Text(
                "而这里，是那个避难所的避难所。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 6.dp).alpha(if (stage >= 3) 1f else 0f)
            )

            Spacer(Modifier.height(32.dp))

            Text(
                "愿世间再无痛苦，唯爱永不独行",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                color = Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(if (stage >= 5) 1f else 0f)
            )
        }
    }
}
