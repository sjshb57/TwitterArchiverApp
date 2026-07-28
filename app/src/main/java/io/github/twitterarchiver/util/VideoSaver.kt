package io.github.twitterarchiver.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** 下载视频保存到系统相册（MediaStore） */
object VideoSaver {

    suspend fun saveVideo(context: Context, url: String, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/TwitterArchiver")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false

                resolver.openOutputStream(uri)?.use { out ->
                    val conn = URL(url).openConnection().apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    conn.getInputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
