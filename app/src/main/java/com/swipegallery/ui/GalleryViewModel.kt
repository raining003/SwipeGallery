package com.swipegallery.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swipegallery.data.ACTION_DELETE
import com.swipegallery.data.ACTION_KEEP
import com.swipegallery.data.ACTION_SKIP
import com.swipegallery.data.Album
import com.swipegallery.data.AlbumLoad
import com.swipegallery.data.AppDatabase
import com.swipegallery.data.CloudAlbum
import com.swipegallery.data.MiGalleryRepo
import com.swipegallery.data.Decision
import com.swipegallery.data.MediaEntry
import com.swipegallery.data.MediaFilter
import com.swipegallery.data.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class GalleryUiState(
    val loading: Boolean = true,
    val filter: MediaFilter = MediaFilter.PHOTOS,
    val items: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    /** 已标记删除、等待系统确认的那批。 */
    val pendingDeleteIds: Set<Long> = emptySet(),
    /** 已经删掉（进了系统回收站）的。 */
    val deletedIds: Set<Long> = emptySet(),
    val error: String? = null,
) {
    val current: MediaEntry? get() = items.getOrNull(index)
    val pendingCount: Int get() = pendingDeleteIds.size

    /** 到结尾了：此时应该提示去处理待删除队列。 */
    val atEnd: Boolean get() = items.isNotEmpty() && index >= items.lastIndex
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)
    private val dao = AppDatabase.get(app).decisionDao()
    private val miGallery = MiGalleryRepo(app)

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    /** 保持插入顺序，方便「再点一次取消」。 */
    private val pending = LinkedHashMap<Long, MediaEntry>()

    fun load(filter: MediaFilter = _state.value.filter) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, filter = filter, error = null) }
            pending.clear()
            try {
                val settled = withContext(Dispatchers.IO) { dao.settledIds().toHashSet() }
                val all = withContext(Dispatchers.IO) { source.loadAll(filter) }
                val queue = all
                    .filterNot { it.id in settled }
                    .shuffled(Random(System.nanoTime()))
                _state.update {
                    it.copy(
                        loading = false,
                        items = queue,
                        index = 0,
                        pendingDeleteIds = emptySet(),
                        deletedIds = emptySet(),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "读取相册失败") }
            }
        }
    }

    /** 翻页落定后同步；向前翻过但没做决定的，记为「跳过」。 */
    fun onPageSettled(page: Int) {
        val snapshot = _state.value
        if (page == snapshot.index) return
        if (page > snapshot.index) {
            val passed = snapshot.items.subList(
                snapshot.index.coerceIn(0, snapshot.items.size),
                page.coerceIn(0, snapshot.items.size),
            )
            if (passed.isNotEmpty()) {
                viewModelScope.launch {
                    passed.forEach { markSkipped(it) }
                }
            }
        }
        _state.update { it.copy(index = page.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))) }
    }

    fun keep() {
        val entry = _state.value.current ?: return
        viewModelScope.launch { decide(entry, ACTION_KEEP) }
        advance()
    }

    fun deleteCurrent() {
        val entry = _state.value.current ?: return
        queueDelete(listOf(entry))
        advance()
    }

    /** 返回上一张：只是把页码退一格，不做隐式的撤销，避免状态混乱。 */
    fun goBack() {
        _state.update { s ->
            if (s.items.isEmpty()) s else s.copy(index = (s.index - 1).coerceAtLeast(0))
        }
    }

    fun goForward() {
        _state.update { s ->
            if (s.items.isEmpty()) s else s.copy(index = (s.index + 1).coerceAtMost(s.items.lastIndex))
        }
    }

    /** 「同时段」面板里批量标记删除。 */
    fun queueDelete(entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { pending[it.id] = it }
        _state.update { it.copy(pendingDeleteIds = pending.keys.toSet()) }
        viewModelScope.launch {
            entries.forEach { decide(it, ACTION_DELETE) }
        }
    }

    /** 取消某一张的删除标记。 */
    fun cancelPending(id: Long) {
        pending.remove(id)
        _state.update { it.copy(pendingDeleteIds = pending.keys.toSet()) }
        viewModelScope.launch { dao.remove(id) }
    }

    fun takeUrisForDelete(): List<Uri> = pending.values.map { it.uri }

    fun onDeleteConfirmed() {
        val ids = pending.keys.toSet()
        pending.clear()
        _state.update {
            it.copy(
                pendingDeleteIds = emptySet(),
                deletedIds = it.deletedIds + ids,
            )
        }
        // 删除完成后，后面的牌会补上来，索引保持不变即可。
    }

    fun onDeleteCancelled() {
        // 用户取消了系统确认框：保留标记，等他再触发一次。
        _state.update { it }
    }

    suspend fun loadAround(centerMs: Long, windowMs: Long): List<MediaEntry> =
        withContext(Dispatchers.IO) { source.loadAround(centerMs, windowMs) }

    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.IO) { source.loadAlbums() }

    /**
     * 把照片挪进某个图集。系统层面其实就是改 RELATIVE_PATH，
     * 文件由 MediaProvider 负责搬，数据库不会打架。
     * 调用前必须先拿 MediaStore.createWriteRequest 的授权。
     */
    suspend fun performMove(entry: MediaEntry, album: Album): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, album.relativePath)
            }
            getApplication<Application>().contentResolver
                .update(entry.uri, values, null, null) > 0
        }.getOrDefault(false)
    }

    /** 移动完成后这张卡已经不在原位置了，直接翻走。 */
    fun afterMove() = goForward()

    /** 从小米相册数据库里读出全部图集（含云图集）。失败时带回具体原因。 */
    suspend fun loadCloudAlbums(): AlbumLoad = miGallery.loadAlbums()

    /** 把照片收进图集：移动文件 + 必要时写相册数据库。返回 >0 成功，0 移动被拒，-1 写库失败。 */
    suspend fun addToAlbum(album: CloudAlbum, entry: MediaEntry): Int =
        miGallery.addToAlbum(album, listOf(entry))

    /** 最近一次收图集失败的原因 */
    val lastAlbumError: String? get() = miGallery.lastError

    private fun advance() {
        _state.update { s ->
            if (s.items.isEmpty()) s
            else s.copy(index = (s.index + 1).coerceAtMost(s.items.lastIndex))
        }
    }

    private suspend fun markSkipped(entry: MediaEntry) {
        if (entry.id in _state.value.pendingDeleteIds) return
        dao.putIfAbsent(
            Decision(
                mediaId = entry.id,
                action = ACTION_SKIP,
                decidedAt = System.currentTimeMillis(),
                dateTaken = entry.dateTaken,
                isVideo = entry.isVideo,
                uri = entry.uri.toString(),
            ),
        )
    }

    private suspend fun decide(entry: MediaEntry, action: String) {
        dao.put(
            Decision(
                mediaId = entry.id,
                action = action,
                decidedAt = System.currentTimeMillis(),
                dateTaken = entry.dateTaken,
                isVideo = entry.isVideo,
                uri = entry.uri.toString(),
            ),
        )
    }
}
