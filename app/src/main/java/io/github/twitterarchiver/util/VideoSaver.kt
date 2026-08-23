package io.github.twitterarchiver.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import io.github.twitterarchiver.data.HttpClients
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
                currentCoroutineContext().ensureActive()
                null
            } ?: return@withContext false

            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw java.io.IOException(AppStrings[R.string.io_open_output_failed])
                out.use { o ->
                    val req = okhttp3.Request.Builder().url(url).build()
                    HttpClients.shared.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        resp.body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
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
