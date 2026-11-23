package com.swipegallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class App : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // 视频要抽帧当封面，必须显式注册解码器。
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
