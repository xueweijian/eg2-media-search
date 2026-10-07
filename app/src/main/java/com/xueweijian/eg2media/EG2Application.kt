package com.xueweijian.eg2media

import android.app.Application
import com.xueweijian.eg2media.index.MediaIndexObserver

/** App 入口：注册 MediaStore 增量索引观察者 */
class EG2Application : Application() {

    private var observer: MediaIndexObserver? = null

    override fun onCreate() {
        super.onCreate()
        observer = MediaIndexObserver(this).also { it.register() }
    }
}
