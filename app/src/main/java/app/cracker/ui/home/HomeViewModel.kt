package app.cracker.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cracker.CrackerApplication
import app.cracker.model.DownloadJob
import app.cracker.model.ExtractResult
import app.cracker.model.VideoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val url: String = "",
    val jobs: List<DownloadJob> = emptyList(),
    val isLoggedIn: Boolean = false,
    val isResolving: Boolean = false,
    val pendingMeta: VideoMeta? = null,
    val selectedQualityId: String? = null,
    val snackbar: String? = null,
    val shareRevision: Long = 0,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CrackerApplication).graph
    private val local = MutableStateFlow(HomeUiState())
    private var resolveJob: Job? = null

    fun receiveShare(text: String) {
        resolveJob?.cancel()
        local.update { it.copy(url = text.trim(), pendingMeta = null, selectedQualityId = null,
            shareRevision = it.shareRevision + 1) }
        submitUrl()
    }

    val state: StateFlow<HomeUiState> = combine(
        local,
        graph.coordinator.jobs,
        graph.cookies.loggedIn,
    ) { ui, jobs, loggedIn ->
        ui.copy(jobs = jobs, isLoggedIn = loggedIn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onUrlChange(value: String) {
        local.update { it.copy(url = value, snackbar = null) }
    }

    fun dismissSnackbar() {
        local.update { it.copy(snackbar = null) }
    }

    fun submitUrl() {
        val raw = local.value.url.trim()
        if (raw.isEmpty()) return
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            local.update { it.copy(isResolving = true, snackbar = null) }
            val result = withContext(Dispatchers.IO) { graph.extractor.resolve(raw) }
            local.update { current ->
                when (result) {
                    is ExtractResult.Ready -> current.copy(
                        isResolving = false,
                        pendingMeta = result.meta,
                        selectedQualityId = result.meta.qualities.firstOrNull()?.id,
                    )
                    is ExtractResult.NeedsLogin -> current.copy(
                        isResolving = false,
                        snackbar = result.reason,
                    )
                    is ExtractResult.Offline -> current.copy(
                        isResolving = false,
                        snackbar = (result.channel?.let { "${it}님은 " } ?: "") + "지금 방송 중이 아니에요",
                    )
                    is ExtractResult.Failed -> current.copy(
                        isResolving = false,
                        snackbar = result.message,
                    )
                }
            }
        }
    }

    fun dismissSheet() {
        local.update { it.copy(pendingMeta = null, selectedQualityId = null) }
    }

    fun selectQuality(id: String) {
        local.update { it.copy(selectedQualityId = id) }
    }

    fun confirmPending() {
        val current = local.value
        val meta = current.pendingMeta ?: return
        if (meta.isAdult && !graph.cookies.loggedIn.value) {
            local.update { it.copy(snackbar = "성인 영상은 네이버 로그인이 필요해요") }
            return
        }
        val quality = meta.qualities.firstOrNull { it.id == current.selectedQualityId }
            ?: meta.qualities.first()
        graph.coordinator.enqueue(meta, quality)
        local.update { it.copy(url = "", pendingMeta = null, selectedQualityId = null) }
    }

    fun cancelJob(id: String) {
        graph.coordinator.cancel(id)
    }

    fun removeJob(id: String) {
        graph.coordinator.remove(id)
    }

    fun clearQueue() {
        graph.coordinator.clearAll()
    }

    fun togglePause(id: String) {
        graph.coordinator.togglePause(id)
    }

    fun onNotificationDenied() {
        local.update { it.copy(snackbar = "알림 없이 다운로드를 시작해요. 진행 상황은 앱에서 확인할 수 있어요") }
    }

    fun logout() {
        graph.cookies.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
    }
}
