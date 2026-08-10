package io.github.twitterarchiver

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import okio.Path.Companion.toOkioPath
import io.github.twitterarchiver.data.HttpClients
import io.github.twitterarchiver.data.ThemeMirror

/** 配置 Coil 全局图片缓存（内存 + 磁盘缓存到 app 私有目录 Android/data） */
class TwitterArchiverApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        ThemeMirror.applyNightMode(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                add(
                    OkHttpNetworkFetcherFactory(callFactory = { HttpClients.images })
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // cacheDir 在 Android/data/<pkg>/cache 下
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024) // 200MB
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
