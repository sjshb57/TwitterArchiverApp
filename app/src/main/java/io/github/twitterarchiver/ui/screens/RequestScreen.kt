package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

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
            title = { Text(stringResource(R.string.request_05), fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            }
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(stringResource(R.string.request_06), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.request_03),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(20.dp))
            val accountInvalid = account.isNotBlank() && !AccountUtil.isValidHandle(account)
            OutlinedTextField(
                value = account, onValueChange = { account = it },
                label = { Text(stringResource(R.string.request_07)) },
                isError = accountInvalid,
                supportingText = if (accountInvalid) {
                    { Text(stringResource(R.string.request_02)) }
                } else null,
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= RequestViewModel.NOTE_MAX) note = it },
                label = { Text(stringResource(R.string.request_01)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
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
                else Text(stringResource(R.string.request_04))
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
