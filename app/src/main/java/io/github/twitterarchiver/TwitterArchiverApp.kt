package io.github.twitterarchiver

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** 配置 Coil 全局图片缓存（内存 + 磁盘缓存到 app 私有目录 Android/data） */
class TwitterArchiverApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // cacheDir 在 Android/data/<pkg>/cache 下
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024) // 200MB
                    .build()
            }
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
            }
            .respectCacheHeaders(false) // 忽略服务器缓存头，强制本地缓存
            .crossfade(true)
            .build()
    }
}
