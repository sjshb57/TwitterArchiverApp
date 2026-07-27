package io.github.twitterarchiver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.bookmarkStore by preferencesDataStore("bookmarks")

/** 一条书签：收藏的推文 */
@Serializable
data class Bookmark(
    val tweetId: String,
    val repo: String,
    val account: String,
    val authorName: String,
    val text: String,
    val date: String,
    val savedAt: Long = System.currentTimeMillis()
)

/** 书签备份文件格式 */
@Serializable
data class BookmarkBackup(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val bookmarks: List<Bookmark> = emptyList()
)

/** 书签管理：本地存 + 导出/导入 */
class Bookmarks(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val key = stringPreferencesKey("bookmark_list")

    val all: Flow<List<Bookmark>> = context.bookmarkStore.data.map { prefs ->
        prefs[key]?.let {
            try { json.decodeFromString<List<Bookmark>>(it) } catch (e: Exception) { emptyList() }
        } ?: emptyList()
    }

    suspend fun add(b: Bookmark) = update { list ->
        if (list.any { it.tweetId == b.tweetId }) list else list + b
    }

    suspend fun remove(tweetId: String) = update { list ->
        list.filterNot { it.tweetId == tweetId }
    }

    private suspend fun update(transform: (List<Bookmark>) -> List<Bookmark>) {
        context.bookmarkStore.edit { prefs ->
            val cur = prefs[key]?.let {
                try { json.decodeFromString<List<Bookmark>>(it) } catch (e: Exception) { emptyList() }
            } ?: emptyList()
            prefs[key] = json.encodeToString(transform(cur))
        }
    }

    /** 导出为 JSON 字符串（备份文件内容） */
    fun exportJson(list: List<Bookmark>): String =
        json.encodeToString(BookmarkBackup(bookmarks = list))

    /** 从备份 JSON 导入（合并去重） */
    suspend fun importJson(content: String): Int {
        val backup = try {
            json.decodeFromString<BookmarkBackup>(content)
        } catch (e: Exception) {
            // 兼容直接是数组的旧格式
            BookmarkBackup(bookmarks = json.decodeFromString(content))
        }
        var added = 0
        update { list ->
            val existing = list.map { it.tweetId }.toSet()
            val merged = list.toMutableList()
            backup.bookmarks.forEach {
                if (it.tweetId !in existing) { merged.add(it); added++ }
            }
            merged
        }
        return added
    }
}
