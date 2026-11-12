package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.FilePreviewDialog
import com.example.ui.screens.BatchAndToolsScreen
import com.example.ui.screens.CompressScreen
import com.example.ui.screens.ConvertScreen
import com.example.ui.screens.HistoryScreen

sealed class NavigationTab(val route: String, val title: String, val icon: ImageVector) {
    object Convert : NavigationTab("convert", "Convert", Icons.Default.Sync)
    object Compress : NavigationTab("compress", "Compress", Icons.Default.Compress)
    object Tools : NavigationTab("tools", "Tools", Icons.Default.Build)
    object History : NavigationTab("history", "History", Icons.Default.History)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniFileApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(NavigationTab.Convert, NavigationTab.Compress, NavigationTab.Tools, NavigationTab.History)
    }

    val selectedFiles by viewModel.selectedFiles.collectAsStateWithLifecycle()
    val targetFormat by viewModel.targetFormat.collectAsStateWithLifecycle()
    val compressionConfig by viewModel.compressionConfig.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val progressStatus by viewModel.progressStatus.collectAsStateWithLifecycle()
    val lastJobResult by viewModel.lastJobResult.collectAsStateWithLifecycle()
    val batchSummary by viewModel.batchSummary.collectAsStateWithLifecycle()
    val sampleFiles by viewModel.sampleFiles.collectAsStateWithLifecycle()
    val historyRecords by viewModel.historyRecords.collectAsStateWithLifecycle()
    val totalSavingsBytes by viewModel.totalSavingsBytes.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalOperationsCount.collectAsStateWithLifecycle()
    val metadataConfig by viewModel.metadataConfig.collectAsStateWithLifecycle()
    val previewFilePath by viewModel.previewFilePath.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Transform,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "OmniFile",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Cozy privacy badge
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "LOCAL",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openDownloadsFolder(context) }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Open Downloads",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                        label = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag("nav_${tab.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ConvertScreen(
                    viewModel = viewModel,
                    selectedFiles = selectedFiles,
                    targetFormat = targetFormat,
                    metadataConfig = metadataConfig,
                    isProcessing = isProcessing,
                    progress = progress,
                    progressStatus = progressStatus,
                    lastJobResult = lastJobResult,
                    batchSummary = batchSummary,
                    sampleFiles = sampleFiles
                )
                1 -> CompressScreen(
                    viewModel = viewModel,
                    selectedFiles = selectedFiles,
                    compressionConfig = compressionConfig,
                    metadataConfig = metadataConfig,
                    isProcessing = isProcessing,
                    progress = progress,
                    progressStatus = progressStatus,
                    lastJobResult = lastJobResult,
                    batchSummary = batchSummary,
                    sampleFiles = sampleFiles
                )
                2 -> BatchAndToolsScreen(viewModel = viewModel)
                3 -> HistoryScreen(
                    viewModel = viewModel,
                    records = historyRecords,
                    totalSavingsBytes = totalSavingsBytes,
                    totalCount = totalCount
                )
            }

            if (previewFilePath != null) {
                FilePreviewDialog(
                    filePath = previewFilePath!!,
                    onDismiss = { viewModel.dismissPreview() },
                    onShare = { viewModel.shareFile(context, previewFilePath!!) }
                )
            }
        }
    }
}