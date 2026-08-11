package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.util.TweetIdUtil
import io.github.twitterarchiver.viewmodel.AdminViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

/** profile.json 序列化：2 空格缩进 + 中文原样，与原版格式一致 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** 编辑账号资料 profile.json：拉取 → 改 name/bio/location/link → 写回（保留其他字段） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminEditProfileScreen(
    vm: AdminViewModel,
    repo: String,
    account: String,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sha by remember { mutableStateOf("") }
    var rawJson by remember { mutableStateOf<JsonObject?>(null) }

    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf("") }

    LaunchedEffect(repo, account) {
        loading = true
        vm.readProfile(repo, account)
            .onSuccess { (content, s) ->
                sha = s
                try {
                    val obj = Json.parseToJsonElement(content).jsonObject
                    rawJson = obj
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    bio = obj["bio"]?.jsonPrimitive?.contentOrNull ?: ""
                    location = obj["location"]?.jsonPrimitive?.contentOrNull ?: ""
                    link = obj["link"]?.jsonPrimitive?.contentOrNull ?: ""
                    pinned = obj["pinned"]?.jsonPrimitive?.contentOrNull ?: ""
                } catch (e: Exception) { error = AppStrings.get(R.string.parse_failed, e.message.orEmpty()) }
                loading = false
            }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        // imePadding 放在根部：键盘弹起时整个 Scaffold 从底部收缩，
        // topBar 仍钉在顶部，内容区自行滚动
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_profile_title, account), fontSize = 15.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (!loading && error == null) Text(stringResource(R.string.save), fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable {
                                // 用改后的字段重建 JSON，保留原有其他字段
                                val base = rawJson
                                if (base != null) {
                                    val merged = buildJsonObject {
                                        base.forEach { (k, v) ->
                                            when (k) {
                                                "name" -> put("name", name)
                                                "bio" -> put("bio", bio)
                                                "location" -> put("location", location)
                                                "link" -> put("link", link)
                                                "pinned" -> put("pinned", TweetIdUtil.normalize(pinned))
                                                else -> put(k, v)
                                            }
                                        }
                                    }
                                    vm.writeProfile(repo, account, prettyJson.encodeToString(JsonObject.serializer(), merged), sha)
                                    onBack()
                                }
                            })
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
            )
        }
    ) { innerPadding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                Text(stringResource(R.string.read_failed, error.orEmpty()), color = MaterialTheme.colorScheme.error)
            }
            else -> Column(
                Modifier.fillMaxSize()
                    .padding(innerPadding)          // 避开固定的 TopAppBar
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Label(stringResource(R.string.admin_profile_03))
                Field(name, { name = it })
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.admin_profile_04))
                Field(bio, { bio = it }, multiline = true)
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.admin_profile_01))
                Field(location, { location = it })
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.admin_profile_06))
                Field(link, { link = it })
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.admin_profile_05))
                // 粘贴推文链接时自动只取末尾 ID，省去手动删前缀
                Field(pinned, { pinned = TweetIdUtil.normalize(it) })
                Text(stringResource(R.string.admin_profile_02),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, multiline: Boolean = false) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = if (multiline) 90.dp else 44.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopStart
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = !multiline,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
