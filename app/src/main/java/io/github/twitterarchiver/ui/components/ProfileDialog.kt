package io.github.twitterarchiver.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.twitterarchiver.data.ArchiveRepo
import io.github.twitterarchiver.data.Profile
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

/**
 * 样式2：点头像弹出的简介卡片。
 * 统计一行显示；含"查看完整存档"和"图片"入口。
 */
@Composable
fun ProfileDialog(
    repo: ArchiveRepo,
    profile: Profile?,
    tweetCount: Int,
    imageCount: Int,
    onDismiss: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenImages: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(
                    url = repo.avatarUrl,
                    size = 64.dp
                )
                Spacer(Modifier.height(10.dp))
                Text(profile?.name?.ifBlank { repo.displayName } ?: repo.displayName,
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(repo.handle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                val bio = profile?.bio ?: repo.description ?: ""
                if (bio.isNotBlank()) {
                    Text(bio, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }

                // 统计一行：数字从 0 快速跳动到最终值
                val animTweets by animateIntAsState(
                    targetValue = tweetCount,
                    animationSpec = tween(durationMillis = 650),
                    label = "tweets"
                )
                val animImages by animateIntAsState(
                    targetValue = imageCount,
                    animationSpec = tween(durationMillis = 650),
                    label = "images"
                )
                Text(
                    stringResource(R.string.profile_stat, animTweets, animImages),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )

                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenReader, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_dlg_01), fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenImages, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_dlg_02), fontSize = 13.sp)
                }
            }
        }
    }
}
