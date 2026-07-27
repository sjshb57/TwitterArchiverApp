package io.github.twitterarchiver.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Bookmark
import io.github.twitterarchiver.data.Bookmarks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarkViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Bookmarks(app)

    val bookmarks: StateFlow<List<Bookmark>> = store.all.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun add(b: Bookmark) { viewModelScope.launch { store.add(b) } }
    fun remove(id: String) { viewModelScope.launch { store.remove(id) } }
    fun exportJson(list: List<Bookmark>): String = store.exportJson(list)
    fun importJson(content: String, onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(store.importJson(content)) }
    }
}
