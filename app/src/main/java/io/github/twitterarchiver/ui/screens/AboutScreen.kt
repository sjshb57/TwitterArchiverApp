package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.ui.theme.Accent

/** 关于页：纪念语 + 版本 + 开源协议 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenThanks: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            ),
            title = { Text("关于", fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        )
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("TwitterArchiver", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground)
            Text("基于 Wayback Machine 的推特账号永久存档", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(40.dp))
            Text("愿世间再无痛苦，唯爱永不独行", fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center, lineHeight = 26.sp)
            Text("烛火熄灭之后，光还在。", fontSize = 12.sp, color = Accent,
                modifier = Modifier.padding(top = 16.dp))

            Spacer(Modifier.height(48.dp))
            Text("版本 1.0.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("本项目开源，遵循 AGPL-3.0 协议", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text("致谢", fontSize = 11.sp, color = Accent,
                modifier = Modifier.padding(top = 14.dp)
                    .clickable { onOpenThanks() }
                    .padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}
