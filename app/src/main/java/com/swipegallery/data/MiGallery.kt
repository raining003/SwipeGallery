package com.swipegallery.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import com.swipegallery.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 小米相册里的一个图集。serverId 为空的是「本地图集」（按目录扫出来的），否则是「云图集」。 */
data class CloudAlbum(
    val id: Long,
    val name: String,
    val localPath: String,
    val serverId: String?,
    val count: Int,
) {
    val relativePath: String get() = localPath.trimEnd('/') + "/"
    val isCloud: Boolean get() = !serverId.isNullOrBlank()
}

/** 读图集列表的结果，失败时带上具体原因，界面上直接显示。 */
sealed interface AlbumLoad {
    data class Ok(val albums: List<CloudAlbum>) : AlbumLoad
    data class Fail(val reason: String) : AlbumLoad
}

/**
 * 直接操作小米相册数据库的桥。
 *
 * 为什么要这么干：小米的「云图集」成员记录存在 gallery.db 的 cloud 表里，
 * 往目录里放文件相册根本不理（实测）。只有往 cloud 表插一条本地记录，
 * 相册才会把它当成新成员收下，并自动同步到小米云。
 *
 * 所有对数据库的写操作都会先备份，失败可以回滚。
 */
class MiGalleryRepo(private val context: Context) {

    companion object {
        private const val DB_DIR = "/data/data/com.miui.gallery/databases"
        private const val DB = "$DB_DIR/gallery.db"
        private const val PKG = "com.miui.gallery"
        private const val STORAGE = "/storage/emulated/0"
    }

    /** root 可用、相册数据库存在，才谈得上写图集。 */
    fun available(): Boolean =
        RootShell.available() && RootShell.run("ls '$DB' 2>/dev/null").isNotBlank()

    suspend fun loadAlbums(): AlbumLoad = withContext(Dispatchers.IO) {
        if (!RootShell.available()) {
            return@withContext AlbumLoad.Fail(
                "拿不到 root 权限。\n\n" +
                    "如果刚才弹出过 KernelSU 的授权请求，请点「允许」后重试；\n" +
                    "也可以手动去 KernelSU → 超级用户 → 给「刷相册」授权。",
            )
        }

        val listing = RootShell.run("ls -la '$DB' 2>&1")
        if (listing.isBlank() || listing.contains("No such file")) {
            return@withContext AlbumLoad.Fail(
                "找不到相册数据库：\n$DB\n\n" +
                    "root 可用，但该路径在当前挂载命名空间里不可见（KernelSU 的 -M 也失败）。",
            )
        }

        val copy = copyDbToCache(includeWal = false)
            ?: return@withContext AlbumLoad.Fail("复制相册数据库到缓存失败。\n\n$DB → ${context.cacheDir}/mig")

        val albums = runCatching {
            val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
            val out = mutableListOf<CloudAlbum>()
            db.rawQuery(
                "SELECT a._id, a.name, a.localPath, a.serverId, " +
                    "(SELECT COUNT(*) FROM cloud c WHERE c.localGroupId = CAST(a._id AS TEXT)) " +
                    "FROM album a WHERE a.localPath IS NOT NULL AND a.localPath != '' " +
                    "ORDER BY 5 DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CloudAlbum(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "",
                            localPath = c.getString(2) ?: "",
                            serverId = if (c.isNull(3)) null else c.getString(3),
                            count = c.getInt(4),
                        ),
                    )
                }
            }
            db.close()
            out.filter { it.name.isNotBlank() && it.localPath.isNotBlank() }
        }.getOrElse { t ->
            copy.delete()
            return@withContext AlbumLoad.Fail("打开数据库失败：${t.javaClass.simpleName} ${t.message}")
        }
        copy.delete()

        if (albums.isEmpty()) {
            AlbumLoad.Fail("数据库能打开，但 album 表里没有可用的图集记录。")
        } else {
            AlbumLoad.Ok(albums)
        }
    }

    /** 最近一次失败的原因，界面上会显示出来，方便定位。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 把照片收进图集。
     * 返回值：>0 成功张数；0 表示移动被系统拒绝（通常是没有写入授权）；-1 表示写数据库失败。
     */
    suspend fun addToAlbum(album: CloudAlbum, entries: List<MediaEntry>): Int =
        withContext(Dispatchers.IO) {
            lastError = null
            if (entries.isEmpty()) return@withContext 0

            val moved = entries.filter { moveFile(it, album.relativePath) }
            if (moved.isEmpty()) {
                lastError = "移动步骤失败（RELATIVE_PATH 没改成功）"
                return@withContext 0
            }

            // 本地图集是按时扫描目录生成的，移动完就不用管了
            if (!album.isCloud) return@withContext moved.size

            if (!available()) {
                lastError = "root 不可用"
                return@withContext -1
            }
            runCatching {
                backupDb()
                stopGallery()
                insertRows(album, moved)
            }.getOrElse { t ->
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                -1
            }
        }

    private fun moveFile(entry: MediaEntry, relativePath: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        context.contentResolver.update(entry.uri, values, null, null) > 0
    }.getOrDefault(false)

    private fun stopGallery() {
        RootShell.run("am force-stop $PKG", timeoutMs = 8000)
    }

    /** 写库前把三件套备份到 app 的外部目录，保留最近 5 份。 */
    private fun backupDb() {
        val dir = File(context.getExternalFilesDir(null), "migbackup")
        dir.mkdirs()
        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "gallery-$stamp")
        out.mkdirs()
        RootShell.run(
            "cp '$DB' '$DB-wal' '$DB-shm' '${out.absolutePath}/' 2>/dev/null",
            timeoutMs = 20000,
        )
        dir.listFiles()?.sortedBy { it.name }?.dropLast(5)?.forEach { it.deleteRecursively() }
    }

    /** 把数据库拉到缓存目录。includeWal=true 时会连同 -wal/-shm 一起拷，读到的才是最新状态。 */
    private fun copyDbToCache(includeWal: Boolean): File? {
        val dir = File(context.cacheDir, "mig").apply { mkdirs() }
        val target = File(dir, "gallery.db")
        listOf("", "-wal", "-shm").forEach { File(dir, "gallery.db$it").delete() }
        val files = if (includeWal) "$DB '$DB-wal' '$DB-shm'" else DB
        RootShell.run("cp $files '${dir.absolutePath}/' 2>/dev/null", timeoutMs = 20000)
        // 关键：cp 出来的是 root 所有、644 权限，app 自己反而写不了。
        // 要开 OPEN_READWRITE 必须给写权限。
        RootShell.run("chmod 666 '${dir.absolutePath}'/* 2>/dev/null")
        return if (target.exists() && target.length() > 0) target else null
    }

    private fun insertRows(album: CloudAlbum, entries: List<MediaEntry>): Int {
        val copy = copyDbToCache(includeWal = true) ?: run {
            lastError = "复制数据库到缓存失败"
            return -1
        }
        val now = System.currentTimeMillis()
        val groupId = album.serverId?.toLongOrNull()
        var inserted = 0

        val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.beginTransaction()
        try {
            entries.forEach { e ->
                val path = "$STORAGE/${album.relativePath}${e.displayName}"
                val name = e.displayName
                val title = name.substringBeforeLast('.')
                db.execSQL("DELETE FROM cloud WHERE fileName = ? AND localGroupId = ?", arrayOf(name, album.id.toString()))
                db.execSQL(
                    """
                    INSERT INTO cloud (
                      groupId, size, dateModified, mimeType, title, fileName, dateTaken, serverType,
                      exifImageWidth, exifImageLength, localFlag, thumbnailFile, localFile,
                      localGroupId, mixedDateTime, realSize, realDateModified, media_store_file_id
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                    arrayOf(
                        groupId, e.sizeBytes, now, e.mime, title, name, e.dateTaken, "1",
                        e.width, e.height, 7, path, path,
                        album.id.toString(), e.dateTaken, e.sizeBytes, now, e.id,
                    ),
                )
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 合并 wal 并切回单文件模式，方便整体替换
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
        runCatching { db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } }
        db.close()

        val writeBack = RootShell.run(
            "cat '${copy.absolutePath}' > '$DB' && echo __OK__",
            timeoutMs = 30000,
        )
        val ok = writeBack.contains("__OK__")
        RootShell.run("rm -f '$DB-wal' '$DB-shm'", timeoutMs = 8000)
        if (!ok) {
            lastError = "写回数据库失败：${writeBack.take(160)}"
            return -1
        }
        return inserted
    }
}
