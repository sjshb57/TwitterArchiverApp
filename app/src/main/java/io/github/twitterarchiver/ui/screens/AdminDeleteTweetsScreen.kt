package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.viewmodel.AdminViewModel
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

/** 删除推文：输入账号仓库 + 推文ID，触发该仓库的删除工作流 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDeleteTweetsScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var repo by remember { mutableStateOf("") }
    var ids by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    if (showConfirm) {
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = stringResource(R.string.admin_del_01),
            message = stringResource(R.string.admin_del_confirm, repo.trim(), ids.trim()),
            confirmText = stringResource(R.string.delete), danger = true,
            onConfirm = {
                vm.triggerWorkflow("home", "delete_tweets.yml",
                    mapOf("target_repo" to repo.trim(), "tweet_ids" to ids.trim(), "account" to account.trim()))
                onBack()
            },
            onDismiss = { showConfirm = false }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.admin_del_01), fontSize = 16.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
        )
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.admin_del_03),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp))
            Field(stringResource(R.string.admin_del_04), repo) { repo = it }
            Spacer(Modifier.height(10.dp))
            Field(stringResource(R.string.admin_del_02), ids) { ids = it }
            Spacer(Modifier.height(10.dp))
            Field(stringResource(R.string.admin_del_05), account) { account = it }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (repo.isNotBlank() && ids.isNotBlank()) showConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.admin_del_01)) }
        }
    }
}

@Composable
private fun Field(hint: String, value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) { inner ->
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        }
    }
}
