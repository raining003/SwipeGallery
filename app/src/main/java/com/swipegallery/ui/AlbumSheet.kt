package com.swipegallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swipegallery.data.AlbumLoad
import com.swipegallery.data.CloudAlbum

/**
 * 「收进图集」选择器。
 * 列表直接读小米相册数据库里的 album 表，所以云图集（Pictures/Gallery 下的那些）也会出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSheet(
    currentAlbum: String,
    result: AlbumLoad?,
    onRetry: () -> Unit,
    onPick: (CloudAlbum) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("收进图集", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "当前在图集「${currentAlbum.ifBlank { "未知" }}」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            when (result) {
                null -> Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("正在读取相册…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                is AlbumLoad.Fail -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        text = result.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = KeepGreen,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }

                is AlbumLoad.Ok -> {
                    val albums = result.albums
                    if (albums.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text("没有可用的图集", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                            items(albums, key = { it.id }) { album ->
                                AlbumRow(
                                    album = album,
                                    isCurrent = album.name == currentAlbum,
                                    onClick = { onPick(album) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AlbumRow(album: CloudAlbum, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (album.isCloud) Color(0xFF2B4C6F) else Color(0xFF3A3A3A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (album.isCloud) "云" else "本",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${album.count} 张 · ${album.localPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (isCurrent) {
            Text("当前", style = MaterialTheme.typography.labelSmall, color = KeepGreen)
            Spacer(Modifier.width(4.dp))
        }
    }
}
