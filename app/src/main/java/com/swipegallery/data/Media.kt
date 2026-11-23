package com.swipegallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.swipegallery.util.RootShell

/** 一张照片或一段视频在媒体库里的最小快照。 */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    /** 拍摄时间（毫秒）。没有 EXIF 时用入库时间兜底。 */
    val dateTaken: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** 所在目录名，也就是系统相册里显示的那个「相册 / 图集」。 */
    val bucket: String,
    val mime: String,
    /** 相对路径，例如 DCIM/Camera/，末尾带斜杠。移动文件时要用。 */
    val relativePath: String,
    val displayName: String,
) {
    val sizeText: String
        get() {
            val mb = sizeBytes / 1024.0 / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${(sizeBytes / 1024)} KB"
        }
}

/** 一个「图集」：可能来自 MediaStore 的目录，也可能是磁盘上还空着的目录。 */
data class Album(
    val key: String,
    val name: String,
    /** 以 / 结尾的相对路径，例如 Pictures/Gallery/owner/ */
    val relativePath: String,
    val count: Int,
    val cover: Uri?,
)

enum class MediaFilter { PHOTOS, VIDEOS }

/**
 * 所有读取都走 MediaStore：快、有缩略图、不耗电。
 */
class MediaStoreSource(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        COL_DURATION,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        COL_BUCKET,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
    )

    fun loadAll(filter: MediaFilter): List<MediaEntry> {
        val type = when (filter) {
            MediaFilter.PHOTOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            MediaFilter.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        }
        val selection = buildString {
            append(MediaStore.Files.FileColumns.MEDIA_TYPE).append(" = ?")
            append(" AND ").append(COL_IS_TRASHED).append(" = 0")
            append(" AND ").append(MediaStore.MediaColumns.SIZE).append(" > 0")
        }
        val out = ArrayList<MediaEntry>(4096)
        resolver.query(
            collection,
            projection,
            selection,
            arrayOf(type.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        )?.use { c ->
            while (c.moveToNext()) out.add(c.toEntry())
        }

        // 兜底：某些 ROM 上 files 集合查不到视频，改用 Video 专用集合再试一次
        if (out.isEmpty() && filter == MediaFilter.VIDEOS) {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) out.add(c.toEntry())
            }
        }
        return out
    }

    /**
     * 取某个时间点前后的所有照片/视频，用来做「同时段」上下文面板。
     */
    fun loadAround(centerMs: Long, windowMs: Long, limit: Int = 400): List<MediaEntry> {
        val from = centerMs - windowMs
        val to = centerMs + windowMs
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ? AND $COL_IS_TRASHED = 0",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(from.toString(), to.toString()),
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_TAKEN} ASC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<MediaEntry>(limit)
        resolver.query(collection, projection, args, null)?.use { c ->
            while (c.moveToNext()) out.add(c.toEntry())
        }
        return out
    }

    /**
     * 列出所有「图集」：
     * 1. MediaStore 里有照片的目录
     * 2. 磁盘上存在但还空着的目录（只能靠 root 列，普通权限读不了别的应用的目录）
     */
    fun loadAlbums(): List<Album> {
        val byPath = LinkedHashMap<String, Album>()
        val albumProjection = arrayOf(
            MediaStore.MediaColumns._ID,
            COL_BUCKET,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,2) AND $COL_IS_TRASHED = 0"

        resolver.query(
            collection,
            albumProjection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketIdx = c.getColumnIndex(COL_BUCKET)
            val pathIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val relPath = if (pathIdx >= 0 && !c.isNull(pathIdx)) c.getString(pathIdx) else null
                    ?: continue
                val name = if (bucketIdx >= 0 && !c.isNull(bucketIdx)) {
                    c.getString(bucketIdx)
                } else {
                    relPath.trimEnd('/').substringAfterLast('/')
                }
                val id = c.getLong(idIdx)
                val existing = byPath[relPath]
                byPath[relPath] = if (existing == null) {
                    Album(
                        key = relPath,
                        name = name,
                        relativePath = relPath,
                        count = 1,
                        cover = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                    )
                } else {
                    existing.copy(count = existing.count + 1)
                }
            }
        }

        // 补上磁盘上还空着的图集目录。
        // 小米的结构是两层：/Pictures/Gallery/<账号容器>/<图集名>/
        // 所以只把「叶子目录」当图集，容器目录（比如 owner）要排除掉。
        galleryAlbumDirs().forEach { (name, relPath) ->
            if (byPath.keys.none { it.equals(relPath, ignoreCase = true) }) {
                byPath[relPath] = Album(
                    key = relPath,
                    name = name,
                    relativePath = relPath,
                    count = 0,
                    cover = null,
                )
            }
        }

        return byPath.values.sortedWith(
            compareByDescending<Album> { it.count }.thenBy { it.name },
        )
    }

    /**
     * 用 root 列出小米图集目录里的「叶子目录」，返回 (显示名, 相对路径)。
     * 没有 root 就返回空。
     */
    private fun galleryAlbumDirs(): List<Pair<String, String>> {
        if (!RootShell.available()) return emptyList()
        val root = "/storage/emulated/0"
        val base = "$root/Pictures/Gallery"
        val out = RootShell.run(
            "find '$base' -mindepth 1 -maxdepth 3 -type d 2>/dev/null",
            timeoutMs = 8000,
        )
        val dirs = out.lines()
            .map { it.trim() }
            .filter { it.startsWith("$base/") && it.length > base.length + 1 }
        if (dirs.isEmpty()) return emptyList()

        val parents = dirs.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }.toSet()
        return dirs
            // 只保留叶子目录，容器（如 owner）会被过滤掉
            .filterNot { it in parents }
            .filterNot { it.substringAfterLast('/').startsWith(".") }
            .mapNotNull { path ->
                val name = path.substringAfterLast('/')
                if (name.isBlank()) return@mapNotNull null
                val rel = path.removePrefix("$root/").trimEnd('/') + "/"
                name to rel
            }
    }

    private fun Cursor.toEntry(): MediaEntry {
        val id = longOf(MediaStore.MediaColumns._ID)
        val type = intOf(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val dateTaken = longOf(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it > 0 }
            ?: (longOf(MediaStore.MediaColumns.DATE_ADDED) * 1000L)
        val baseUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return MediaEntry(
            id = id,
            uri = ContentUris.withAppendedId(baseUri, id),
            isVideo = isVideo,
            dateTaken = dateTaken,
            durationMs = longOf(COL_DURATION),
            width = intOf(MediaStore.MediaColumns.WIDTH),
            height = intOf(MediaStore.MediaColumns.HEIGHT),
            sizeBytes = longOf(MediaStore.MediaColumns.SIZE),
            bucket = stringOf(COL_BUCKET) ?: "",
            mime = stringOf(MediaStore.MediaColumns.MIME_TYPE) ?: if (isVideo) "video/*" else "image/*",
            relativePath = stringOf(MediaStore.MediaColumns.RELATIVE_PATH) ?: "",
            displayName = stringOf(MediaStore.MediaColumns.DISPLAY_NAME) ?: "",
        )
    }

    private fun Cursor.longOf(column: String): Long {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else 0L
    }

    private fun Cursor.intOf(column: String): Int {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getInt(i) else 0
    }

    private fun Cursor.stringOf(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    companion object {
        // 用字面量而不是常量，避免个别 ROM 上常量为 null 导致的崩溃。
        private const val COL_DURATION = "duration"
        private const val COL_BUCKET = "bucket_display_name"
        private const val COL_IS_TRASHED = "is_trashed"
    }
}
