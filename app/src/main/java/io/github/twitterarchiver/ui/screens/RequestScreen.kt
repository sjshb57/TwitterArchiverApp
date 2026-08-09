package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.viewmodel.RequestViewModel
import io.github.twitterarchiver.util.AccountUtil

/** 申请存档页（访客用受限 token 提交 Issue） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestScreen(
    vm: RequestViewModel,
    restrictedToken: String,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var account by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            ),
            title = { Text("申请存档", fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("申请存档一个推特账号", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
            Text("提交后我们会尽快处理。请填写想要存档的账号用户名。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = account, onValueChange = { account = it },
                label = { Text("账号用户名（如 @example）") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= RequestViewModel.NOTE_MAX) note = it },
                label = { Text("备注（可选）") },
                supportingText = { Text("${note.length} / ${RequestViewModel.NOTE_MAX}") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.submit(restrictedToken, account, note) },
                enabled = AccountUtil.isValidHandle(account) && !state.submitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.submitting) CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Text("提交申请")
            }

            state.result?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 12.sp,
                    color = if (state.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
            }
        }
    }
}
