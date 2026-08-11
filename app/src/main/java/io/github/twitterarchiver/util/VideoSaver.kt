package io.github.twitterarchiver.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

/** 下载视频保存到系统相册。minSdk 30，一律走分区存储，无需存储权限 */
object VideoSaver {

    suspend fun saveVideo(context: Context, url: String, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/TwitterArchiver"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                null
            } ?: return@withContext false

            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw java.io.IOException(AppStrings[R.string.io_open_output_failed])
                out.use { o ->
                    val conn = URL(url).openConnection().apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    conn.getInputStream().use { input ->
                        input.copyTo(o, bufferSize = 64 * 1024)
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                if (e is CancellationException) throw e
                false
            }
        }
}
