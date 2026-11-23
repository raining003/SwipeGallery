package com.swipegallery.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.swipegallery.data.MediaEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaPage(
    entry: MediaEntry,
    isCurrent: Boolean,
    isDeleted: Boolean,
    isPendingDelete: Boolean,
    onOpenContext: () -> Unit,
    onOpenInGallery: () -> Unit,
    onAddToAlbum: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isDeleted -> DeletedPlaceholder()
            entry.isVideo -> VideoStage(entry = entry, isCurrent = isCurrent)
            else -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(entry.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        if (isCurrent && !isDeleted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isPendingDelete) {
                        Badge("已标记删除", DeleteRed)
                    }
                    if (entry.isVideo) {
                        Badge(formatDuration(entry.durationMs), Color(0xCC000000))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatDate(entry.dateTaken),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        entry.bucket.takeIf { it.isNotBlank() }?.let { "图集 $it" },
                        entry.sizeText,
                        "${entry.width}×${entry.height}".takeIf { entry.width > 0 && entry.height > 0 },
                    ).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = onOpenContext,
                        label = { Text("看同时段") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.14f),
                        ),
                        border = null,
                    )
                    AssistChip(
                        onClick = onAddToAlbum,
                        label = { Text("收进图集") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.14f),
                        ),
                        border = null,
                    )
                    if (isPendingDelete) {
                        AssistChip(
                            onClick = onCancelDelete,
                            label = { Text("撤销删除") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = Color.White,
                                containerColor = DeleteRed,
                            ),
                            border = null,
                        )
                    }
                    AssistChip(
                        onClick = onOpenInGallery,
                        label = { Text("去相册看") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.14f),
                        ),
                        border = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DeletedPlaceholder() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("已移入回收站", color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(6.dp))
        Text(
            "可以在小米相册的「回收站」里找回",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoStage(entry: MediaEntry, isCurrent: Boolean) {
    val context = LocalContext.current
    var playing by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) playing = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            val player = remember(entry.id) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(entry.uri))
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) {
                onDispose { player.release() }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(entry.uri)
                    .videoFrameMillis(0)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Box(
                modifier = Modifier
                    .clickable { playing = true }
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("播放", color = Color.White)
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) String.format(Locale.US, "%d:%02d", m, s) else String.format(Locale.US, "0:%02d", s)
}
