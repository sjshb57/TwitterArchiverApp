package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.HealthSort
import io.github.twitterarchiver.data.RepoHealth
import io.github.twitterarchiver.viewmodel.AdminViewModel
import io.github.twitterarchiver.viewmodel.DashRepo
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoHealthScreen(
    vm: AdminViewModel,
    onBack: () -> Unit,
    onOpenDash: (DashRepo) -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenPrivate: () -> Unit,
    privateOnly: Boolean = false
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var localSort by remember { mutableStateOf(HealthSort.STALE) }
    val sort = if (privateOnly) localSort else state.healthSort

    LaunchedEffect(Unit) { if (state.health.isEmpty()) vm.loadHealth() }

    val privateRepos = state.health.filter { it.private }
    val base = if (privateOnly) privateRepos else state.health.filter { !it.private }
    val shown = when (sort) {
        HealthSort.STALE -> base.sortedByDescending { it.daysSincePush ?: -1 }
        HealthSort.SIZE -> base.sortedByDescending { it.sizeKb }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (privateOnly) stringResource(R.string.health_07) else stringResource(R.string.health_02),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Text("‹", fontSize = 30.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp).clickable { onBack() })
                },
                actions = {
                    Text(if (state.healthLoading) stringResource(R.string.admin_new_13) else stringResource(R.string.health_09),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp).clickable { vm.loadHealth() })
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (!privateOnly) {
                item {
                    val r = state.rotation
                    Text(
                        when {
                            state.healthLoading -> stringResource(R.string.health_05)
                            r != null -> stringResource(
                                R.string.health_rotation,
                                state.health.size, r.batch * r.runsPerDay, r.cycleDays, r.overdueDays
                            )
                            else -> stringResource(R.string.health_total, state.health.size)
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    state.healthError?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }

            if (!privateOnly) {
                item {
                    EntryRow(
                        stringResource(R.string.health_07),
                        if (state.healthLoading && state.health.isEmpty()) stringResource(R.string.health_08)
                        else stringResource(R.string.health_private_total, privateRepos.size),
                        onOpenPrivate
                    )
                }
                item {
                    EntryRow(stringResource(R.string.health_03), stringResource(R.string.health_01)) {
                        onOpenDash(DashRepo.STARTER)
                    }
                }
            }

            if (!state.healthLoading && state.health.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.images_screen_01), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        HealthSort.entries.forEach { sortMode ->
                            val on = sort == sortMode
                            Text(
                                stringResource(sortMode.labelRes), fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        if (privateOnly) localSort = sortMode
                                        else vm.setHealthSort(sortMode)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }

            items(shown, key = { it.name }) { h -> HealthRow(h) { onOpenRepo(h.name) } }

            if (state.healthLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}

@Composable
private fun HealthRow(h: RepoHealth, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (h.private) {
                    Text(stringResource(R.string.health_06), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(
                h.daysSincePush?.let { stringResource(R.string.health_days_ago, it) } ?: stringResource(R.string.health_04),
                fontSize = 12.sp,
                color = if (h.overdue) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (h.sizeLevel > 0) {
            val c = if (h.sizeLevel == 2) MaterialTheme.colorScheme.error else Color(0xFFF5A623)
            Text(
                "%.1f GB".format(h.sizeMb / 1024),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        } else {
            Text(
                if (h.sizeMb >= 1) "%.0f MB".format(h.sizeMb) else "%.1f MB".format(h.sizeMb),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}
