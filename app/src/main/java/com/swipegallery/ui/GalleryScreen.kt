package com.swipegallery.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipegallery.data.Album
import com.swipegallery.data.AlbumLoad
import com.swipegallery.data.CloudAlbum
import com.swipegallery.data.MediaEntry
import com.swipegallery.data.MediaFilter
import kotlinx.coroutines.launch

/** 攒够多少张再弹一次系统删除确认。 */
private const val FLUSH_THRESHOLD = 15

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(vm: GalleryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var albumEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var albumLoad by remember { mutableStateOf<AlbumLoad?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onDeleteConfirmed() else vm.onDeleteCancelled()
    }

    var pendingAdd by remember { mutableStateOf<Pair<MediaEntry, CloudAlbum>?>(null) }

    val addLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val job = pendingAdd
        pendingAdd = null
        if (result.resultCode == Activity.RESULT_OK && job != null) {
            scope.launch {
                syncing = true
                val code = runCatching { vm.addToAlbum(job.second, job.first) }.getOrDefault(-1)
                syncing = false
                val msg = when {
                    code > 0 -> "已收进「${job.second.name}」"
                    code == 0 -> "移动失败：系统没给写入权限"
                    else -> "写库失败：${vm.lastAlbumError ?: "未知原因"}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (code > 0) vm.afterMove()
            }
        }
    }

    fun flushDeletes() {
        val uris = vm.takeUrisForDelete()
        if (uris.isEmpty()) return
        runCatching {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
        }.onFailure {
            Toast.makeText(context, "发起删除失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val hasVideoAccess = remember(state.filter) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) { vm.load(MediaFilter.PHOTOS) }

    LaunchedEffect(state.pendingCount) {
        if (state.pendingCount >= FLUSH_THRESHOLD) flushDeletes()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        TopBar(
            state = state,
            onFilter = { vm.load(it) },
            onBack = { vm.goBack() },
            onFlush = { flushDeletes() },
            onDiag = { showDiag = true },
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KeepGreen)
                }

                state.error != null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "", color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { vm.load(state.filter) }) { Text("重试") }
                    }
                }

                state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.filter == MediaFilter.VIDEOS && !hasVideoAccess) {
                            Text(
                                "读不到视频",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "「照片」和「视频」在系统里是两个独立权限，现在只给了照片。\n去设置里把视频也打开。",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { openAppSettings(context) }) { Text("去设置") }
                        } else {
                            Text("这一轮刷完了", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "已经处理过的不会再出现。删掉的可以去小米相册回收站找回。",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { vm.load(state.filter) }) { Text("重新开始") }
                        }
                    }
                }

                else -> {
                    val pagerState = rememberPagerState(initialPage = 0) { state.items.size }

                    LaunchedEffect(state.index) {
                        if (
                            state.items.isNotEmpty() &&
                            pagerState.currentPage != state.index &&
                            !pagerState.isScrollInProgress
                        ) {
                            pagerState.scrollToPage(state.index)
                        }
                    }

                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.settledPage }.collect { vm.onPageSettled(it) }
                    }

                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { index -> state.items[index].id },
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val entry = state.items.getOrNull(page)
                        if (entry != null) {
                            MediaPage(
                                entry = entry,
                                isCurrent = page == pagerState.currentPage,
                                isDeleted = entry.id in state.deletedIds,
                                isPendingDelete = entry.id in state.pendingDeleteIds,
                                onOpenContext = { sheetEntry = entry },
                                onOpenInGallery = { openInGallery(context, entry) },
                                onAddToAlbum = {
                                    albumEntry = entry
                                    albumLoad = null
                                    scope.launch { albumLoad = vm.loadCloudAlbums() }
                                },
                                onCancelDelete = { vm.cancelPending(entry.id) },
                            )
                        }
                    }

                    // 投屏/用鼠标时滑动不方便，这两个按钮直接翻页
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PageNavButton(
                            icon = Icons.Rounded.KeyboardArrowUp,
                            description = "上一张",
                            enabled = state.index > 0,
                            onClick = { vm.goBack() },
                        )
                        PageNavButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            description = "下一张",
                            enabled = state.index < state.items.lastIndex,
                            onClick = { vm.goForward() },
                        )
                    }
                }
            }
        }

        BottomBar(
            enabled = !state.loading && state.current != null,
            onDelete = { vm.deleteCurrent() },
            onKeep = { vm.keep() },
        )
    }

    sheetEntry?.let { entry ->
        ContextSheet(
            entry = entry,
            vm = vm,
            onDismiss = { sheetEntry = null },
        )
    }

    albumEntry?.let { entry ->
        AlbumSheet(
            currentAlbum = entry.bucket,
            result = albumLoad,
            onRetry = {
                albumLoad = null
                scope.launch { albumLoad = vm.loadCloudAlbums() }
            },
            onPick = { album ->
                albumEntry = null
                albumLoad = null
                runCatching {
                    // 必须先拿系统写入授权，否则改不了别人创建的文件的 RELATIVE_PATH
                    val pi = MediaStore.createWriteRequest(
                        context.contentResolver,
                        listOf(entry.uri),
                    )
                    pendingAdd = entry to album
                    addLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }.onFailure {
                    Toast.makeText(context, "发起移动失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = {
                albumEntry = null
                albumLoad = null
            },
        )
    }

    if (syncing) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = KeepGreen)
                Spacer(Modifier.height(14.dp))
                Text("正在写入小米相册…", color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "会短暂关闭相册，完成后重新打开即可",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showDiag) {
        DiagDialog(onDismiss = { showDiag = false })
    }
}

@Composable
private fun PageNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.16f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f),
        )
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "打不开设置页", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TopBar(
    state: GalleryUiState,
    onFilter: (MediaFilter) -> Unit,
    onBack: () -> Unit,
    onFlush: () -> Unit,
    onDiag: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回上一张",
                tint = Color.White,
            )
        }

        FilterTabs(current = state.filter, onSelect = onFilter)

        Spacer(Modifier.weight(1f))

        Text(
            text = if (state.items.isEmpty()) "" else "${state.index + 1}/${state.items.size}",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
        )

        IconButton(onClick = onDiag) {
            Icon(
                Icons.Rounded.BugReport,
                contentDescription = "诊断",
                tint = Color.White.copy(alpha = 0.35f),
            )
        }

        IconButton(onClick = onFlush) {
            Box {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "确认删除",
                    tint = if (state.pendingCount > 0) DeleteRed else Color.White.copy(alpha = 0.5f),
                )
                if (state.pendingCount > 0) {
                    Text(
                        text = state.pendingCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(DeleteRed, RoundedCornerShape(50))
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTabs(current: MediaFilter, onSelect: (MediaFilter) -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TabPill("照片", current == MediaFilter.PHOTOS) { onSelect(MediaFilter.PHOTOS) }
        TabPill("视频", current == MediaFilter.VIDEOS) { onSelect(MediaFilter.VIDEOS) }
    }
}

@Composable
private fun TabPill(text: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(32.dp)
            .then(
                if (active) Modifier.background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                else Modifier,
            ),
    ) {
        Text(
            text = text,
            color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun BottomBar(
    enabled: Boolean,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeleteRed,
                contentColor = Color.White,
            ),
        ) {
            Text("删除", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onKeep,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KeepGreen,
                contentColor = Color.Black,
            ),
        ) {
            Text("留下", fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun openInGallery(context: android.content.Context, entry: MediaEntry) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "没有找到能打开它的相册应用", Toast.LENGTH_SHORT).show()
    }
}
