package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    ApkExtractorApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Extraction helper for standard Uri (Single Save)
suspend fun extractApkToUri(context: Context, sourceDir: String, destUri: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = File(sourceDir).inputStream()
            val outputStream = context.contentResolver.openOutputStream(destUri)
            if (outputStream != null) {
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

// Extraction helper for Directory Tree (Bulk Backup)
suspend fun extractApkToTree(context: Context, sourceDir: String, treeUri: Uri, displayName: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            val fileUri = DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                "application/vnd.android.package-archive",
                displayName
            ) ?: return@withContext false

            val inputStream = File(sourceDir).inputStream()
            val outputStream = resolver.openOutputStream(fileUri) ?: return@withContext false

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkExtractorApp(
    modifier: Modifier = Modifier,
    viewModel: AppExtractorViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appList by viewModel.appList.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val selectedCount by viewModel.selectedAppsCount.collectAsStateWithLifecycle()

    var expandedAppPackageName by remember { mutableStateOf<String?>(null) }

    // Launcher for saving a single APK
    val singleSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        if (uri != null) {
            val pendingApp = uiState.singleExtractionApp
            if (pendingApp != null) {
                scope.launch {
                    viewModel.startBackupProgress("Backing up ${pendingApp.name}...")
                    val success = extractApkToUri(context, pendingApp.sourceDir, uri)
                    viewModel.endBackupProgress()
                    viewModel.setSingleExtractionApp(null)
                    if (success) {
                        Toast.makeText(context, "Successfully backed up ${pendingApp.name}!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to backup ${pendingApp.name}.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            viewModel.setSingleExtractionApp(null)
        }
    }

    // Launcher for bulk-saving folder selection
    val bulkSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val selectedApps = appList.filter { it.isSelected }
            if (selectedApps.isNotEmpty()) {
                scope.launch {
                    var backedUpCount = 0
                    var failedCount = 0
                    val totalCount = selectedApps.size

                    viewModel.startBackupProgress("Preparing bulk backup...")

                    selectedApps.forEachIndexed { index, app ->
                        viewModel.updateBackupProgress(
                            progress = index.toFloat() / totalCount,
                            message = "Backing up ${app.name} (${index + 1}/$totalCount)..."
                        )
                        val sanitizedName = "${app.name.replace("/", "_").replace(" ", "_")}_v${app.versionName}.apk"
                        val success = extractApkToTree(context, app.sourceDir, uri, sanitizedName)
                        if (success) {
                            backedUpCount++
                        } else {
                            failedCount++
                        }
                    }

                    viewModel.endBackupProgress()
                    viewModel.clearSelection()

                    val message = if (failedCount == 0) {
                        "Successfully backed up $backedUpCount apps!"
                    } else {
                        "Backed up $backedUpCount apps. Failed to backup: $failedCount."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Load initial packages
    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps(context)
    }

    val isDark = isSystemInDarkTheme()
    val baseGradient = if (isDark) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A), // Slate 900
                Color(0xFF1E1B4B), // Indigo 950
                Color(0xFF020617)  // Black-slate
            )
        )
    } else {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFDF8FF), // Pastel Lavender tint
                Color(0xFFEFF6FF), // Soft Blue
                Color(0xFFFAF5FF)  // Soft Purple
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseGradient)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Dashboard / Header Layout
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0x261E1B4B) else Color(0xCCFFFFFF)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0x26FFFFFF) else Color(0x66FFFFFF)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    // Beautiful generated hero banner
                    Image(
                        painter = painterResource(id = R.drawable.apk_extractor_banner),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                        alpha = if (isDark) 0.15f else 0.25f
                    )

                    // Linear Gradient Overlay for text readability
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, if (isDark) Color(0xCC020617) else Color(0x88000000)),
                                    startY = 100f
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // App Name & Branding
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), shape = CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Backup,
                                        contentDescription = null,
                                        tint = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "APK Extractor",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.SansSerif
                                        ),
                                        color = if (isDark) Color.White else Color(0xFF1D1B20)
                                    )
                                    Text(
                                        text = "Backup, Share & Manage",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F)
                                    )
                                }
                            }

                            // Refresh Action
                            IconButton(
                                onClick = { viewModel.loadInstalledApps(context) },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(if (isDark) Color(0x33FFFFFF) else Color(0xFFE8DEF8), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh App List",
                                    tint = if (isDark) Color.White else Color(0xFF21005D),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Stat row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                label = "Total",
                                value = appList.size.toString(),
                                icon = Icons.Default.Apps,
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                label = "System",
                                value = appList.count { it.isSystemApp }.toString(),
                                icon = Icons.Default.SettingsSystemDaydream,
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                label = "User",
                                value = appList.count { !it.isSystemApp }.toString(),
                                icon = Icons.Default.Person,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Interactive Search and Control bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0x1F000000) else Color(0x99FFFFFF)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0x1AFFFFFF) else Color(0x4DFFFFFF)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search Bar
                    TextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search apps by name or package...") },
                        leadingIcon = { 
                            Icon(
                                Icons.Default.Search, 
                                contentDescription = "Search", 
                                tint = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F)
                            ) 
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 56.dp)
                            .testTag("app_search_input"),
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = if (isDark) Color(0x33FFFFFF) else Color(0xFFF3EFF4),
                            unfocusedContainerColor = if (isDark) Color(0x1AFFFFFF) else Color(0xFFF3EFF4),
                            disabledContainerColor = if (isDark) Color(0x1AFFFFFF) else Color(0xFFF3EFF4),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )

                    // Filters and Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Type Selection Chips
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterTypeChip(
                                label = "All",
                                selected = uiState.filterType == FilterType.ALL,
                                onClick = { viewModel.setFilterType(FilterType.ALL) }
                            )
                            FilterTypeChip(
                                label = "User",
                                selected = uiState.filterType == FilterType.USER,
                                onClick = { viewModel.setFilterType(FilterType.USER) }
                            )
                            FilterTypeChip(
                                label = "System",
                                selected = uiState.filterType == FilterType.SYSTEM,
                                onClick = { viewModel.setFilterType(FilterType.SYSTEM) }
                            )
                        }

                        // Sorting Dropdown Trigger
                        var showSortMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier
                                    .testTag("sort_button")
                                    .background(if (isDark) Color(0x1AFFFFFF) else Color(0xFFF3EFF4), shape = CircleShape)
                            ) {
                                val sortIcon = when (uiState.sortType) {
                                    SortType.NAME -> Icons.Default.SortByAlpha
                                    SortType.SIZE -> Icons.Default.SdStorage
                                    SortType.INSTALL_TIME -> Icons.Default.Schedule
                                }
                                Icon(
                                    imageVector = sortIcon, 
                                    contentDescription = "Sort Apps",
                                    tint = if (isDark) Color.White else Color(0xFF49454F)
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(if (isDark) Color(0xFF1E1B4B) else Color(0xFFFFFFFF))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Name") },
                                    leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                                    onClick = {
                                        viewModel.setSortType(SortType.NAME)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by File Size") },
                                    leadingIcon = { Icon(Icons.Default.SdStorage, null) },
                                    onClick = {
                                        viewModel.setSortType(SortType.SIZE)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by Install Date") },
                                    leadingIcon = { Icon(Icons.Default.Schedule, null) },
                                    onClick = {
                                        viewModel.setSortType(SortType.INSTALL_TIME)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // App List Section
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = if (isDark) Color(0xFF818CF8) else Color(0xFF6750A4)
                    )
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = if (isDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = if (uiState.searchQuery.isNotEmpty()) "No applications match your search" else "No applications found",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Quick helper to select all filtered apps at once
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Showing ${filteredApps.size} apps",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
                                fontWeight = FontWeight.Bold
                            )

                            TextButton(
                                onClick = { viewModel.selectAllFiltered() },
                                modifier = Modifier.testTag("select_all_button"),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isDark) Color(0xFF818CF8) else Color(0xFF6750A4)
                                )
                            ) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Select All")
                            }
                        }
                    }

                    items(filteredApps, key = { it.packageName }) { app ->
                        AppItemCard(
                            app = app,
                            isExpanded = app.packageName == expandedAppPackageName,
                            onHeaderClick = {
                                expandedAppPackageName = if (expandedAppPackageName == app.packageName) {
                                    null
                                } else {
                                    app.packageName
                                }
                            },
                            onSelectionToggle = {
                                viewModel.toggleAppSelection(app.packageName)
                            },
                            onShareClick = {
                                shareAppApk(context, app)
                            },
                            onExtractClick = {
                                viewModel.setSingleExtractionApp(app)
                                val sanitizedName = "${app.name.replace("/", "_").replace(" ", "_")}_v${app.versionName}.apk"
                                singleSaveLauncher.launch(sanitizedName)
                            }
                        )
                    }
                }
            }
        }

        // Floating Action Sheet / Bottom Panel for Bulk Extract
        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val selectedSizeFormatted = remember(appList) {
                val totalBytes = appList.filter { it.isSelected }.sumOf { it.sizeBytes }
                val mb = totalBytes / (1024.0 * 1024.0)
                if (mb < 0.1) {
                    String.format("%.1f KB", totalBytes / 1024.0)
                } else {
                    String.format("%.2f MB", mb)
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = if (isDark) Color(0xE61E1B4B) else Color(0xE6F3EFF4),
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (isDark) Color(0x4DFFFFFF) else Color(0x80FFFFFF)
                ),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "$selectedCount selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color(0xFF1D1B20)
                        )
                        Text(
                            text = "Total Size: $selectedSizeFormatted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF49454F),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.clearSelection() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isDark) Color(0xFFF87171) else Color(0xFFB3261E)
                            )
                        ) {
                            Text("Clear")
                        }

                        Button(
                            onClick = { bulkSaveLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF6366F1) else Color(0xFF6750A4),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .testTag("bulk_extract_button")
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extract APKs")
                        }
                    }
                }
            }
        }

        // Full Screen Progress Dialog
        if (uiState.isBackupProgressActive) {
            Dialog(
                onDismissRequest = { /* Cannot dismiss during backup */ },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isDark) Color(0xE60F172A) else Color(0xE6FFFFFF),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isDark) Color(0x33FFFFFF) else Color(0x80FFFFFF)
                    ),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        CircularProgressIndicator(
                            color = if (isDark) Color(0xFF818CF8) else Color(0xFF6750A4),
                            strokeWidth = 4.dp
                        )
                        Text(
                            text = "Extracting & Copying",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color(0xFF1D1B20)
                        )
                        Text(
                            text = uiState.backupMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF49454F),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        LinearProgressIndicator(
                            progress = { uiState.backupProgress },
                            color = if (isDark) Color(0xFF818CF8) else Color(0xFF6750A4),
                            trackColor = if (isDark) Color(0x1AFFFFFF) else Color(0xFFEADDFF),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemCard(
    app: AppInfo,
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    onSelectionToggle: () -> Unit,
    onShareClick: () -> Unit,
    onExtractClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    
    val containerColor = if (isDark) {
        if (app.isSelected) Color(0x406366F1) else Color(0x1F0F172A)
    } else {
        if (app.isSelected) Color(0xCCEADDFF) else Color(0xB3FFFFFF)
    }
    
    val borderColor = if (app.isSelected) {
        if (isDark) Color(0xFF818CF8) else Color(0xFF6750A4)
    } else {
        if (isDark) Color(0x1AFFFFFF) else Color(0x4DFFFFFF)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onHeaderClick)
            .testTag("app_item_${app.packageName}"),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (app.isSelected) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive Checkbox for Bulk Selection
                Checkbox(
                    checked = app.isSelected,
                    onCheckedChange = { onSelectionToggle() },
                    modifier = Modifier.testTag("checkbox_${app.packageName}"),
                    colors = CheckboxDefaults.colors(
                        checkedColor = if (isDark) Color(0xFF6366F1) else Color(0xFF6750A4)
                    )
                )

                Spacer(modifier = Modifier.width(4.dp))

                // App Icon using standard robust ImageView representation
                AppIconImage(packageName = app.packageName)

                Spacer(modifier = Modifier.width(12.dp))

                // App Title and Info Summary
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isDark) Color.White else Color(0xFF1C1B1F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF49454F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge indicating User vs System
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (app.isSystemApp) {
                                if (isDark) Color(0xFF7F1D1D) else Color(0xFFFFD9D9)
                            } else {
                                if (isDark) Color(0xFF1E3A8A) else Color(0xFFE0F2FE)
                            }
                        ) {
                            Text(
                                text = if (app.isSystemApp) "System" else "User",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = if (app.isSystemApp) {
                                    if (isDark) Color(0xFFFECACA) else Color(0xFFBA1A1A)
                                } else {
                                    if (isDark) Color(0xFFBFDBFE) else Color(0xFF0369A1)
                                }
                            )
                        }

                        // Size Text Badge
                        Text(
                            text = app.sizeFormatted,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F)
                        )
                    }
                }

                // Expand/Collapse Chevron Indicator
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F)
                )
            }

            // Expanding Detail View
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0x1F000000) else Color(0x33F3EFF4))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = if (isDark) Color(0x1AFFFFFF) else Color(0xFFE6E1E5))

                    // Metadata detail list
                    DetailField(label = "Version", value = "${app.versionName} (${app.versionCode})")
                    DetailField(label = "Installed", value = formatDateTime(app.installTime))
                    DetailField(label = "Updated", value = formatDateTime(app.updateTime))
                    DetailField(label = "Source Path", value = app.sourceDir, isCode = true)

                    Spacer(modifier = Modifier.height(4.dp))

                    // Detail Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share Action
                        FilledTonalButton(
                            onClick = onShareClick,
                            modifier = Modifier.testTag("share_button_${app.packageName}"),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isDark) Color(0x33FFFFFF) else Color(0xCCF3EFF4),
                                contentColor = if (isDark) Color.White else Color(0xFF21005D)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share APK")
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Backup/Extract Action
                        Button(
                            onClick = onExtractClick,
                            modifier = Modifier.testTag("extract_button_${app.packageName}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF6366F1) else Color(0xFF6750A4),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailField(label: String, value: String, isCode: Boolean = false) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF1D1B20),
            modifier = Modifier.width(90.dp)
        )
        Text(
            text = value,
            style = if (isCode) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F),
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = if (isDark) Color(0xFF6366F1) else Color(0xFFEADDFF),
            selectedLabelColor = if (isDark) Color.White else Color(0xFF21005D),
            containerColor = Color.Transparent,
            labelColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (isDark) Color(0x33FFFFFF) else Color(0xFF79747E),
            selectedBorderColor = Color.Transparent
        )
    )
}

@Composable
fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0x26FFFFFF) else Color(0x66FFFFFF)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDark) Color(0x1AFFFFFF) else Color(0x336750A4)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDark) Color.White else Color(0xFF6750A4),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isDark) Color.White else Color(0xFF1D1B20)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF49454F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AppIconImage(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val icon = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                color = if (isDark) Color(0x33FFFFFF) else Color(0xFFFFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(icon)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun shareAppApk(context: Context, app: AppInfo) {
    try {
        val srcFile = File(app.sourceDir)
        if (!srcFile.exists()) {
            Toast.makeText(context, "Source APK not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Save under a nice directory in cache
        val cacheDir = File(context.cacheDir, "extracted_apks")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val destFile = File(cacheDir, "${app.name.replace(" ", "_")}_v${app.versionName}.apk")

        srcFile.copyTo(destFile, overwrite = true)

        val uri = FileProvider.getUriForFile(
            context,
            "com.aistudio.apkextractor.pksvmd.fileprovider",
            destFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${app.name} APK"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error sharing APK: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

private fun formatDateTime(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        "-"
    }
}

