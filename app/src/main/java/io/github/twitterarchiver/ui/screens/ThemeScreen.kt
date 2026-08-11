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
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

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
            title = { Text(stringResource(R.string.settings_02), fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            }
        )

        SectionTitle(stringResource(R.string.theme_04))
        Group {
            ThemeOption(stringResource(R.string.theme_06), current == ThemeMode.LIGHT) { onSetTheme(ThemeMode.LIGHT) }
            Div()
            ThemeOption(stringResource(R.string.theme_07), current == ThemeMode.DARK) { onSetTheme(ThemeMode.DARK) }
            Div()
            ThemeOption(stringResource(R.string.theme_09), current == ThemeMode.SYSTEM) { onSetTheme(ThemeMode.SYSTEM) }
        }

        SectionTitle(stringResource(R.string.theme_02))
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
                        if (supported) stringResource(R.string.theme_10) else stringResource(R.string.theme_01),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Switch(checked = dynamicColor && supported, enabled = supported,
                    onCheckedChange = onSetDynamic)
            }
        }

        SectionTitle(stringResource(R.string.theme_05))
        Group {
            ThemeOption(stringResource(R.string.theme_08), barStyle == "text") { onSetBarStyle("text") }
            Div()
            ThemeOption(stringResource(R.string.theme_03), barStyle == "icon_text") { onSetBarStyle("icon_text") }
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
    if (selected) Icon(Icons.Filled.Check, stringResource(R.string.deftab_02), tint = MaterialTheme.colorScheme.primary)
}

@Composable private fun Div() = HorizontalDivider(
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
    modifier = Modifier.padding(horizontal = 14.dp)
)
