package dev.tlong.biodex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.backup.BackupService
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.photo.grantPressure
import dev.tlong.biodex.data.settings.AppSettings
import dev.tlong.biodex.media.CacheManager
import dev.tlong.biodex.media.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, cache management and S01 (ARCHITECTURE.md 4.4, 4.5, 5.3). Every decision worth
 * testing is in `SettingsState.kt` or under `data/backup/`; this class sequences them and
 * keeps the screen honest about what is in flight.
 */
class SettingsViewModel(
    private val settings: AppSettings,
    private val caches: CacheManager,
    private val backups: BackupService,
    private val photos: PhotoGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) { caches.sizes() }
            val grants = withContext(Dispatchers.IO) { photos.persistedGrantCount() }
            _uiState.update {
                it.copy(
                    keepLocalCopy = settings.keepLocalCopyNow(),
                    cacheSizes = sizes,
                    grantCount = grants,
                    grantPressure = grantPressure(grants),
                )
            }
        }
    }

    /**
     * S03. Takes effect on the *next* registration and is never retroactive — ARCHITECTURE.md
     * 4.5 rules out copying old captures, and the screen says so beside the switch.
     */
    fun setKeepLocalCopy(enabled: Boolean) {
        settings.setKeepLocalCopy(enabled)
        _uiState.update { it.copy(keepLocalCopy = enabled) }
    }

    fun clearReferenceCaches() {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = SettingsBusy.CLEARING, message = null) }
        viewModelScope.launch {
            val reclaimed = withContext(Dispatchers.IO) { caches.clearReferenceCaches() }
            val sizes = withContext(Dispatchers.IO) { caches.sizes() }
            _uiState.update {
                it.copy(
                    busy = null,
                    cacheSizes = sizes,
                    message = "Cleared ${formatBytes(reclaimed)} of cached images. " +
                        "Your photos, thumbnails and entries are untouched.",
                    messageIsWarning = false,
                )
            }
        }
    }

    fun export() {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = SettingsBusy.EXPORTING, message = null) }
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { backups.export() }) {
                is BackupService.ExportResult.Success -> {
                    _events.send(SettingsEvent.ShareArchive(result.shareUri, result.fileName))
                    _uiState.update {
                        it.copy(
                            busy = null,
                            message = exportSummary(result.fileName, result.report),
                            messageIsWarning = !result.report.complete,
                        )
                    }
                }

                BackupService.ExportResult.NothingToExport -> _uiState.update {
                    it.copy(
                        busy = null,
                        message = "Nothing to export yet — register a species first.",
                        messageIsWarning = false,
                    )
                }

                is BackupService.ExportResult.Failed -> _uiState.update {
                    it.copy(busy = null, message = result.reason, messageIsWarning = true)
                }
            }
        }
    }

    fun import(archiveUri: String) {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = SettingsBusy.IMPORTING, message = null) }
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { backups.import(archiveUri) }) {
                is BackupService.ImportResult.Success -> _uiState.update {
                    it.copy(
                        busy = null,
                        message = importSummary(result.report),
                        messageIsWarning = result.report.capturesWithoutSpecies > 0,
                    )
                }

                is BackupService.ImportResult.Failed -> _uiState.update {
                    it.copy(busy = null, message = result.reason, messageIsWarning = true)
                }
            }
            refresh()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.settings,
                    caches = container.cacheManager,
                    backups = container.backupService,
                    photos = container.photoGateway,
                )
            }
        }
    }
}
