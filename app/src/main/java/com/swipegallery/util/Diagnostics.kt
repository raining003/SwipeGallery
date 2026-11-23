package com.swipegallery.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * 采集环境信息，方便用户反馈问题时附上。
 * 只读取本机公开信息，不上传任何内容。
 */
object Diagnostics {

    private const val GALLERY_DB = "/data/data/com.miui.gallery/databases/gallery.db"

    fun build(context: Context): String {
        val sb = StringBuilder()

        sb.append("== 系统 ==\n")
        sb.append("SDK ").append(Build.VERSION.SDK_INT)
        sb.append(" / ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("root: ").append(if (RootShell.available()) "可用" else "不可用").append("\n\n")

        sb.append("== 权限 ==\n")
        sb.append("READ_MEDIA_IMAGES: ").append(perm(context, Manifest.permission.READ_MEDIA_IMAGES)).append('\n')
        sb.append("READ_MEDIA_VIDEO: ").append(perm(context, Manifest.permission.READ_MEDIA_VIDEO)).append('\n')
        sb.append("READ_MEDIA_VISUAL_USER_SELECTED: ")
            .append(perm(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)).append("\n\n")

        sb.append("== MediaStore 计数 ==\n")
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        sb.append("图片: ").append(count(context, files, "media_type = 1", null)).append('\n')
        sb.append("视频: ").append(count(context, files, "media_type = 2", null)).append('\n')
        sb.append("未回收的视频: ")
            .append(count(context, files, "media_type = 2 AND is_trashed = 0", null)).append("\n\n")

        sb.append("== 相册数据库（图集功能依赖）==\n")
        val listing = RootShell.run("ls -la '$GALLERY_DB' 2>&1")
        sb.append(if (listing.isBlank()) "(读不到)" else listing.trim()).append("\n\n")

        sb.append("== root 测试 ==\n")
        sb.append("id:\n    ").append(RootShell.probe("id")).append('\n')
        sb.append("ls gallery.db:\n    ").append(RootShell.probe("ls -la '$GALLERY_DB'")).append('\n')

        return sb.toString()
    }

    private fun perm(context: Context, permission: String): String {
        if (Build.VERSION.SDK_INT < 33 && permission != Manifest.permission.READ_EXTERNAL_STORAGE) {
            return "不适用"
        }
        return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            "已授予"
        } else {
            "未授予"
        }
    }

    private fun count(
        context: Context,
        uri: Uri,
        selection: String?,
        args: Array<String>?,
    ): String = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            ?.use { it.count.toString() } ?: "null"
    } catch (t: Throwable) {
        "ERR(${t.javaClass.simpleName})"
    }
}
