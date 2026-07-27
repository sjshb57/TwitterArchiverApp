package io.github.twitterarchiver.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.data.ThemeMode

/** 主题管理二级页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    current: ThemeMode,
    dynamicColor: Boolean,
    barStyle: String,
    onSetTheme: (ThemeMode) -> Unit,
    onSetDynamic: (Boolean) -> Unit,
    onSetBarStyle: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            ),
            title = { Text("主题管理", fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        )

        SectionTitle("外观模式")
        Group {
            ThemeOption("浅色", current == ThemeMode.LIGHT) { onSetTheme(ThemeMode.LIGHT) }
            Div()
            ThemeOption("深色", current == ThemeMode.DARK) { onSetTheme(ThemeMode.DARK) }
            Div()
            ThemeOption("跟随系统", current == ThemeMode.SYSTEM) { onSetTheme(ThemeMode.SYSTEM) }
        }

        SectionTitle("动态配色")
        Group {
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Material You", fontSize = 14.sp,
                        color = if (supported) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (supported) "跟随系统壁纸取色" else "仅支持 Android 12+",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Switch(checked = dynamicColor && supported, enabled = supported,
                    onCheckedChange = onSetDynamic)
            }
        }

        SectionTitle("底栏样式")
        Group {
            ThemeOption("纯文字", barStyle == "text") { onSetBarStyle("text") }
            Div()
            ThemeOption("图标 + 文字", barStyle == "icon_text") { onSetBarStyle("icon_text") }
        }
    }
}

@Composable private fun SectionTitle(t: String) = Text(
    t, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
)

@Composable private fun Group(content: @Composable () -> Unit) = Surface(
    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
) { Column { content() } }

@Composable private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f))
    if (selected) Icon(Icons.Filled.Check, "已选", tint = MaterialTheme.colorScheme.primary)
}

@Composable private fun Div() = HorizontalDivider(
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
    modifier = Modifier.padding(horizontal = 14.dp)
)
