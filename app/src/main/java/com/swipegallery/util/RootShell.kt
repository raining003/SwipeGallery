package com.swipegallery.util

import java.util.concurrent.TimeUnit

/**
 * 极简 root 调用。
 *
 * 关键坑（踩了很久）：KernelSU / Magisk 给每个应用单独的 mount namespace，
 * 默认情况下那个 namespace 里看不到别的应用的数据目录
 * （比如 /data/data/com.miui.gallery）。
 * 结果就是 root 明明拿到了（id 输出 uid=0），读相册数据库却报 No such file。
 *
 * 解决办法：用 `su -M --mount-master`，强制在全局挂载命名空间执行。
 */
object RootShell {

    @Volatile
    private var confirmedRoot = false

    /** 是否使用 -M（全局挂载命名空间）。默认 true，探测失败后回退。 */
    @Volatile
    private var useMountMaster = true

    /** 只缓存「成功」，失败下次还会重试。 */
    fun available(): Boolean {
        if (confirmedRoot) return true

        if (runWith(true, "id", 20_000).contains("uid=0")) {
            useMountMaster = true
            confirmedRoot = true
            return true
        }
        if (runWith(false, "id", 20_000).contains("uid=0")) {
            useMountMaster = false
            confirmedRoot = true
            return true
        }
        return false
    }

    fun reset() {
        confirmedRoot = false
    }

    fun run(command: String, timeoutMs: Long = 8000): String =
        runWith(useMountMaster, command, timeoutMs)

    /** 诊断用：分别用两种模式执行，方便看出差异。 */
    fun probe(command: String, timeoutMs: Long = 20_000): String {
        val master = runWith(true, command, timeoutMs)
        val plain = runWith(false, command, timeoutMs)
        return buildString {
            append("-M(全局): ").append(master.trim().take(200).ifBlank { "(空)" })
            append("\n  普通    : ").append(plain.trim().take(200).ifBlank { "(空)" })
        }
    }

    private fun runWith(master: Boolean, command: String, timeoutMs: Long): String {
        val args = if (master) listOf("su", "-M", "-c", command) else listOf("su", "-c", command)
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                ""
            } else {
                output
            }
        } catch (t: Throwable) {
            ""
        }
    }
}
