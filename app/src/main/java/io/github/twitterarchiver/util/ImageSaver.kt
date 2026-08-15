package io.github.twitterarchiver.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 保存图片到系统相册。minSdk 30，一律走分区存储，无需存储权限 */
object ImageSaver {

    suspend fun saveBitmap(context: Context, bitmap: Bitmap, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/TwitterArchiver"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                null
            } ?: return@withContext false

            try {
                val out: OutputStream = resolver.openOutputStream(uri)
                    ?: throw java.io.IOException(AppStrings[R.string.io_open_output_failed])
                out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                if (e is CancellationException) throw e
                false
            }
        }
}
