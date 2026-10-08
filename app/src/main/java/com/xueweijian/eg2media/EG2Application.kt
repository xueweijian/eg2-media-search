package com.xueweijian.eg2media

import android.app.Application
import com.xueweijian.eg2media.index.MediaIndexObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EG2Application : Application() {

    private var observer: MediaIndexObserver? = null

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        observer = MediaIndexObserver(this).also { it.register() }
    }

    /** 闪退取证：任何未捕获异常落盘 files/crash-log.txt（保留尾部 48KB），
     *  诊断导出时附带——用户侧闪退终于有据可查 */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = buildString {
                    append("=== $ts [${t.name}] ${e.javaClass.name}: ${e.message}\n")
                    e.stackTrace.take(24).forEach { append("  at $it\n") }
                    var cause = e.cause
                    var depth = 0
                    while (cause != null && depth < 3) {
                        append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                        cause.stackTrace.take(8).forEach { append("  at $it\n") }
                        cause = cause.cause
                        depth++
                    }
                    append("\n")
                }
                val f = File(filesDir, "crash-log.txt")
                val existing = if (f.exists()) f.readText() else ""
                f.writeText((existing + entry).takeLast(48 * 1024))
            }
            prev?.uncaughtException(t, e)
        }
    }
}
