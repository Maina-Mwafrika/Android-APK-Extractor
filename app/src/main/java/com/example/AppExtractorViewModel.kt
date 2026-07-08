package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

enum class FilterType {
    ALL, USER, SYSTEM
}

enum class SortType {
    NAME, SIZE, INSTALL_TIME
}

data class AppExtractorUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val filterType: FilterType = FilterType.ALL,
    val sortType: SortType = SortType.NAME,
    val isBackupProgressActive: Boolean = false,
    val backupProgress: Float = 0f,
    val backupMessage: String = "",
    val singleExtractionApp: AppInfo? = null
)

class AppExtractorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppExtractorUiState())
    val uiState: StateFlow<AppExtractorUiState> = _uiState.asStateFlow()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    val filteredApps: StateFlow<List<AppInfo>> = combine(_uiState, _appList) { state, apps ->
        var result = apps

        // Filter by search query
        if (state.searchQuery.isNotBlank()) {
            val query = state.searchQuery.trim().lowercase()
            result = result.filter {
                it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }

        // Filter by type
        result = when (state.filterType) {
            FilterType.ALL -> result
            FilterType.USER -> result.filter { !it.isSystemApp }
            FilterType.SYSTEM -> result.filter { it.isSystemApp }
        }

        // Sort
        result = when (state.sortType) {
            SortType.NAME -> result.sortedWith(compareBy { it.name.lowercase() })
            SortType.SIZE -> result.sortedByDescending { it.sizeBytes }
            SortType.INSTALL_TIME -> result.sortedByDescending { it.installTime }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedAppsCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun loadInstalledApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val pm = context.packageManager
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0)
                }

                val apps = packages.mapNotNull { packageInfo ->
                    val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                    
                    val apkFile = File(appInfo.sourceDir)
                    if (!apkFile.exists()) return@mapNotNull null
                    val sizeBytes = apkFile.length()

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    val name = appInfo.loadLabel(pm).toString()
                    
                    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }

                    AppInfo(
                        packageName = packageInfo.packageName,
                        name = name,
                        versionName = packageInfo.versionName ?: "1.0",
                        versionCode = versionCode,
                        sourceDir = appInfo.sourceDir,
                        sizeBytes = sizeBytes,
                        isSystemApp = isSystem,
                        installTime = packageInfo.firstInstallTime,
                        updateTime = packageInfo.lastUpdateTime
                    )
                }

                _appList.value = apps
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleAppSelection(packageName: String) {
        _appList.update { currentList ->
            currentList.map { app ->
                if (app.packageName == packageName) {
                    app.copy(isSelected = !app.isSelected)
                } else {
                    app
                }
            }
        }
    }

    fun clearSelection() {
        _appList.update { currentList ->
            currentList.map { it.copy(isSelected = false) }
        }
    }

    fun selectAllFiltered() {
        val filteredList = filteredApps.value.map { it.packageName }.toSet()
        _appList.update { currentList ->
            currentList.map { app ->
                if (filteredList.contains(app.packageName)) {
                    app.copy(isSelected = true)
                } else {
                    app
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterType(filterType: FilterType) {
        _uiState.update { it.copy(filterType = filterType) }
    }

    fun setSortType(sortType: SortType) {
        _uiState.update { it.copy(sortType = sortType) }
    }

    fun setSingleExtractionApp(app: AppInfo?) {
        _uiState.update { it.copy(singleExtractionApp = app) }
    }

    fun startBackupProgress(message: String) {
        _uiState.update {
            it.copy(
                isBackupProgressActive = true,
                backupProgress = 0f,
                backupMessage = message
            )
        }
    }

    fun updateBackupProgress(progress: Float, message: String) {
        _uiState.update {
            it.copy(
                backupProgress = progress,
                backupMessage = message
            )
        }
    }

    fun endBackupProgress() {
        _uiState.update {
            it.copy(
                isBackupProgressActive = false,
                backupProgress = 0f,
                backupMessage = ""
            )
        }
    }
}
