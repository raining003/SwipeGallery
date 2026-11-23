package com.swipegallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.swipegallery.data.MediaEntry

enum class TimeWindow(val label: String, val millis: Long) {
    TWO_HOURS("±2 小时", 2 * 60 * 60 * 1000L),
    SAME_DAY("同一天", 12 * 60 * 60 * 1000L),
    THREE_DAYS("±3 天", 3 * 24 * 60 * 60 * 1000L),
}

/**
 * 「同时段」面板：把某张图前后一段时间的所有照片/视频铺成网格，
 * 因为单看一张截图很难判断该不该删，放到上下文里就容易多了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    entry: MediaEntry,
    vm: GalleryViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var window by remember(entry.id) { mutableStateOf(TimeWindow.TWO_HOURS) }
    var selected by remember(entry.id) { mutableStateOf<Set<Long>>(emptySet()) }

    val items by produceState(
        initialValue = emptyList<MediaEntry>(),
        entry.id,
        window,
    ) {
        value = vm.loadAround(entry.dateTaken, window.millis)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "同时段 · ${items.size} 张",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDate(entry.dateTaken),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeWindow.entries.forEach { w ->
                    FilterChip(
                        selected = window == w,
                        onClick = { window = w },
                        label = { Text(w.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            val isSelected = item.id in selected
                            val ctx = LocalContext.current
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.DarkGray)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(3.dp, DeleteRed, RoundedCornerShape(4.dp))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable {
                                        selected = if (isSelected) {
                                            selected - item.id
                                        } else {
                                            selected + item.id
                                        }
                                    },
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(ctx)
                                        .data(item.uri)
                                        .apply { if (item.isVideo) videoFrameMillis(0) }
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                    contentScale = ContentScale.Crop,
                                )
                                if (item.isVideo) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(2.dp)
                                            .size(16.dp),
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = DeleteRed,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val chosen = items.filter { it.id in selected }
                    vm.queueDelete(chosen)
                    onDismiss()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DeleteRed),
            ) {
                Text(if (selected.isEmpty()) "选择要删的" else "标记删除 ${selected.size} 张")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
