package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.DownloadStorageHelper
import com.example.engine.FileInspector
import com.example.model.FileDetails
import com.example.ui.MainViewModel
import com.example.ui.theme.EmeraldSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Composable
fun BatchAndToolsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Zip Multiple Files", "Split File", "Put Back Together (Join)", "Base64 & Hex")

    // Tool 0: Multi-File Zip State
    var selectedBatchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedBatchDetails by remember { mutableStateOf<List<FileDetails>>(emptyList()) }
    var archiveName by remember { mutableStateOf("Archive_${System.currentTimeMillis() % 10000}") }
    var zipCompressionLevel by remember { mutableIntStateOf(9) } // 0=Store, 1=Fast, 5=Normal, 9=Ultra L9
    var batchZipStatus by remember { mutableStateOf<String?>(null) }
    var isZipping by remember { mutableStateOf(false) }
    var zipProgress by remember { mutableStateOf(0f) }
    var createdZipInfo by remember { mutableStateOf<com.example.engine.SavedFileInfo?>(null) }

    val batchFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedBatchUris = uris
            batchZipStatus = null
            createdZipInfo = null
            scope.launch {
                selectedBatchDetails = withContext(Dispatchers.IO) {
                    uris.map { FileInspector.inspectUri(context, it) }
                }
            }
        }
    }

    // Tool 1: Splitter State
    var splitterFile by remember { mutableStateOf<FileDetails?>(null) }
    var splitChunkMb by remember { mutableStateOf("10") }
    var splitStatus by remember { mutableStateOf<String?>(null) }
    var isSplitting by remember { mutableStateOf(false) }
    var generatedPartsCount by remember { mutableIntStateOf(0) }
    var lastSplitParts by remember { mutableStateOf<List<com.example.engine.SavedFileInfo>>(emptyList()) }

    val splitterFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            splitterFile = FileInspector.inspectUri(context, it)
            splitStatus = null
            generatedPartsCount = 0
            lastSplitParts = emptyList()
        }
    }

    // Tool 2: Join / Merge Parts State
    var joinUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var joinDetails by remember { mutableStateOf<List<FileDetails>>(emptyList()) }
    var joinStatus by remember { mutableStateOf<String?>(null) }
    var isJoining by remember { mutableStateOf(false) }
    var mergedResultInfo by remember { mutableStateOf<com.example.engine.SavedFileInfo?>(null) }
    var customMergedName by remember { mutableStateOf("") }

    val joinFilesPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            joinUris = uris
            joinStatus = null
            mergedResultInfo = null
            scope.launch {
                val details = withContext(Dispatchers.IO) {
                    uris.map { FileInspector.inspectUri(context, it) }
                        .sortedBy { it.name }
                }
                joinDetails = details
            }
        }
    }

    // Tool 3: Base64 / Hex State
    var rawInputText by remember { mutableStateOf("OmniFile Universal Engine: High performance local processing!") }
    var transformedOutput by remember { mutableStateOf("") }
    var transformMode by remember { mutableStateOf("Encode Base64") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Selector
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            tabTitles.forEachIndexed { idx, title ->
                val tabIcon = when (idx) {
                    0 -> Icons.Default.FolderZip
                    1 -> Icons.Default.CallSplit
                    2 -> Icons.Default.MergeType
                    else -> Icons.Default.Code
                }
                Tab(
                    selected = selectedTabIndex == idx,
                    onClick = { selectedTabIndex = idx },
                    icon = { Icon(imageVector = tabIcon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTabIndex == idx) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedTabIndex) {
            // TAB 0: MULTI-FILE ZIP ARCHIVER
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FolderZip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Multi-File ZIP Archiver",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = "Select any number of files and compress them together into a single ultra-compact .zip archive saved directly to Downloads/OmniFile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { batchFilePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_files_to_zip_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedBatchDetails.isEmpty()) "Select Files to Bundle & Zip" else "${selectedBatchDetails.size} Files Selected (${FileDetails.formatBytes(selectedBatchDetails.sumOf { it.size })})"
                            )
                        }

                        if (selectedBatchDetails.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // File list preview
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                selectedBatchDetails.take(5).forEach { f ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = f.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(text = f.formattedSize, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (selectedBatchDetails.size > 5) {
                                    Text(
                                        text = "+ ${selectedBatchDetails.size - 5} more files",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Archive Name
                            OutlinedTextField(
                                value = archiveName,
                                onValueChange = { archiveName = it.replace(Regex("[^a-zA-Z0-9_\\-]"), "") },
                                label = { Text("ZIP Archive Name") },
                                suffix = { Text(".zip") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Compression Level Chips
                            Text(
                                text = "Compression Level:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(Pair("Store (0)", 0), Pair("Fast (1)", 1), Pair("Normal (5)", 5), Pair("Ultra L9 (9)", 9)).forEach { (lbl, lvl) ->
                                    FilterChip(
                                        selected = zipCompressionLevel == lvl,
                                        onClick = { zipCompressionLevel = lvl },
                                        label = { Text(text = lbl, fontSize = 11.sp) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (isZipping) {
                                LinearProgressIndicator(
                                    progress = { zipProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = batchZipStatus ?: "Compressing files...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isZipping = true
                                            zipProgress = 0.1f
                                            batchZipStatus = "Packing and compressing ${selectedBatchDetails.size} files..."

                                            val result = createAndSaveBatchZip(
                                                context = context,
                                                files = selectedBatchDetails,
                                                archiveName = "$archiveName.zip",
                                                compressionLevel = zipCompressionLevel,
                                                onProgress = { p, status ->
                                                    zipProgress = p
                                                    batchZipStatus = status
                                                }
                                            )

                                            createdZipInfo = result
                                            isZipping = false
                                            batchZipStatus = "ZIP created: ${result.fileName} (${FileDetails.formatBytes(result.sizeBytes)})"
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create_zip_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(imageVector = Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create ZIP Archive")
                                }
                            }
                        }

                        if (createdZipInfo != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldSuccess.copy(alpha = 0.12f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Saved in Downloads/OmniFile",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = EmeraldSuccess
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${createdZipInfo!!.fileName} (${FileDetails.formatBytes(createdZipInfo!!.sizeBytes)})",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.openDownloadsFolder(context) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Open Folder", fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = { viewModel.shareFile(context, createdZipInfo!!.absolutePath) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Share ZIP", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 1: FILE SPLITTER
            1 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CallSplit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Target Chunk Splitter",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = "Split any oversized file into exact equal part chunks (.part001, .part002...) saved in Downloads/OmniFile so it can bypass upload limits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (splitterFile == null) {
                            OutlinedButton(
                                onClick = { splitterFilePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("select_split_file_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.UploadFile, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select File to Split")
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = splitterFile!!.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = splitterFile!!.formattedSize,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { splitterFilePicker.launch(arrayOf("*/*")) },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Change", fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = splitChunkMb,
                                onValueChange = { splitChunkMb = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Max Target Size per Part (MB)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            val chunkBytes = ((splitChunkMb.toDoubleOrNull() ?: 1.0) * 1024 * 1024).toLong().coerceAtLeast(1024L)
                            val estParts = (splitterFile!!.size / chunkBytes) + (if (splitterFile!!.size % chunkBytes != 0L) 1 else 0)

                            Text(
                                text = "Will generate ~$estParts parts of max ${splitChunkMb} MB each",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        isSplitting = true
                                        splitStatus = "Splitting into exact parts..."
                                        val parts = splitAndSaveFile(context, splitterFile!!, chunkBytes)
                                        lastSplitParts = parts
                                        generatedPartsCount = parts.size
                                        isSplitting = false
                                        splitStatus = "Generated ${parts.size} parts and saved to Downloads/OmniFile!"
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("split_now_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Split File Into Parts")
                            }
                        }

                        if (splitStatus != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldSuccess.copy(alpha = 0.12f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = splitStatus!!,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = EmeraldSuccess
                                    )

                                    if (lastSplitParts.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            lastSplitParts.take(6).forEach { part ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(text = part.fileName, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                    Text(text = FileDetails.formatBytes(part.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            if (lastSplitParts.size > 6) {
                                                Text(text = "+ ${lastSplitParts.size - 6} more parts", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        // Convert split parts to FileDetails and transfer to Joiner
                                                        val details = withContext(Dispatchers.IO) {
                                                            lastSplitParts.mapNotNull { p ->
                                                                p.contentUri?.let { uri -> FileInspector.inspectUri(context, uri) }
                                                                    ?: File(p.absolutePath).takeIf { it.exists() }?.let { FileInspector.inspectFile(it) }
                                                            }
                                                        }
                                                        if (details.isNotEmpty()) {
                                                            joinDetails = details
                                                            customMergedName = splitterFile?.name ?: "restored_file"
                                                        }
                                                        selectedTabIndex = 2 // Switch to Join tab
                                                    }
                                                },
                                                modifier = Modifier.weight(1.3f),
                                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(imageVector = Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Put Back Together in Joiner", fontSize = 11.sp)
                                            }

                                            OutlinedButton(
                                                onClick = { viewModel.openDownloadsFolder(context) },
                                                modifier = Modifier.weight(0.9f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Downloads", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: JOIN / MERGE PARTS (PUT FILES BACK TOGETHER)
            2 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MergeType,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Put Back Together (Join Files)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = "Reassemble split chunks (.part001, .part002...) back into the exact original intact file! Saved directly to Downloads/OmniFile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Quick Load from Splitter button if parts were recently generated
                        if (lastSplitParts.isNotEmpty() && joinDetails.isEmpty()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val details = withContext(Dispatchers.IO) {
                                            lastSplitParts.mapNotNull { p ->
                                                p.contentUri?.let { uri -> FileInspector.inspectUri(context, uri) }
                                                    ?: File(p.absolutePath).takeIf { it.exists() }?.let { FileInspector.inspectFile(it) }
                                            }
                                        }
                                        joinDetails = details
                                        customMergedName = splitterFile?.name ?: "restored_file"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                Icon(imageVector = Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Load ${lastSplitParts.size} Parts from Recent Split (${splitterFile?.name})", fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Button(
                            onClick = { joinFilesPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_parts_to_join_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (joinDetails.isEmpty()) "Select Split Parts to Merge" else "${joinDetails.size} Parts Selected (${FileDetails.formatBytes(joinDetails.sumOf { it.size })})"
                            )
                        }

                        if (joinDetails.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // Guessed original filename
                            val firstPartName = joinDetails.first().name
                            val detectedOriginalName = remember(joinDetails) {
                                if (customMergedName.isNotEmpty()) {
                                    customMergedName
                                } else if (firstPartName.contains(".part")) {
                                    firstPartName.substringBefore(".part")
                                } else if (firstPartName.matches(Regex(".*\\.\\d{3}$"))) {
                                    firstPartName.substringBeforeLast('.')
                                } else {
                                    "restored_file_${System.currentTimeMillis() % 10000}"
                                }
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "DETECTED RECONSTRUCTION TARGET:",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = detectedOriginalName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Total Combined Size: ${FileDetails.formatBytes(joinDetails.sumOf { it.size })} across ${joinDetails.size} chunks",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Parts list preview
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                joinDetails.take(6).forEach { part ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = part.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Text(text = part.formattedSize, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (isJoining) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = joinStatus ?: "Merging streams...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isJoining = true
                                            joinStatus = "Merging ${joinDetails.size} chunks into $detectedOriginalName..."

                                            val result = mergeAndSaveParts(
                                                context = context,
                                                parts = joinDetails,
                                                targetFileName = detectedOriginalName
                                            )

                                            mergedResultInfo = result
                                            isJoining = false
                                            joinStatus = "Successfully merged! Saved to Downloads/OmniFile."
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("merge_now_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldSuccess
                                    )
                                ) {
                                    Icon(imageVector = Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Reassemble & Merge Chunks")
                                }
                            }
                        }

                        if (mergedResultInfo != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldSuccess.copy(alpha = 0.15f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Intact Original File Restored!",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = EmeraldSuccess
                                    )
                                    Text(
                                        text = "${mergedResultInfo!!.fileName} (${FileDetails.formatBytes(mergedResultInfo!!.sizeBytes)})",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.showPreview(mergedResultInfo!!.absolutePath) },
                                            modifier = Modifier.weight(1.2f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Inspect / Play", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.openFile(context, mergedResultInfo!!.absolutePath) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Open", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.shareFile(context, mergedResultInfo!!.absolutePath) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Share", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: BASE64 & HEX MATRIX
            3 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Universal Data Matrix",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = "Encode and decode raw strings, binary streams, Base64, and Hex dumps instantly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = rawInputText,
                            onValueChange = { rawInputText = it },
                            label = { Text("Input Content") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val modes = listOf("Encode Base64", "Decode Base64", "Hex Dump", "Hex to Text")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            modes.forEach { m ->
                                FilterChip(
                                    selected = transformMode == m,
                                    onClick = {
                                        transformMode = m
                                        transformedOutput = when (m) {
                                            "Encode Base64" -> Base64.encodeToString(rawInputText.toByteArray(), Base64.DEFAULT)
                                            "Decode Base64" -> try {
                                                String(Base64.decode(rawInputText.trim(), Base64.DEFAULT))
                                            } catch (e: Exception) { "Invalid Base64: ${e.message}" }
                                            "Hex Dump" -> rawInputText.toByteArray().joinToString(" ") { String.format("%02X", it) }
                                            else -> try {
                                                val bytes = rawInputText.trim().split("\\s+".toRegex()).map { it.toInt(16).toByte() }.toByteArray()
                                                String(bytes)
                                            } catch (e: Exception) { "Invalid Hex: ${e.message}" }
                                        }
                                    },
                                    label = { Text(text = m, fontSize = 10.sp) }
                                )
                            }
                        }

                        if (transformedOutput.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "OUTPUT ($transformMode):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = transformedOutput,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Multi-File ZIP Archiving Helper ---
suspend fun createAndSaveBatchZip(
    context: Context,
    files: List<FileDetails>,
    archiveName: String,
    compressionLevel: Int,
    onProgress: (Float, String) -> Unit
): com.example.engine.SavedFileInfo = withContext(Dispatchers.IO) {
    val tempZipFile = File(context.cacheDir, "temp_pack_${System.currentTimeMillis()}.zip")

    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile))).use { zos ->
        zos.setLevel(compressionLevel)
        val buf = ByteArray(32768)

        files.forEachIndexed { idx, f ->
            val p = (idx.toFloat() / files.size.toFloat())
            onProgress(p, "Compressing ${idx + 1}/${files.size}: ${f.name}...")

            val entry = ZipEntry(f.name)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(f.uri)?.use { inStream ->
                var read: Int
                while (inStream.read(buf).also { read = it } != -1) {
                    zos.write(buf, 0, read)
                }
            }
            zos.closeEntry()
        }
    }

    onProgress(0.95f, "Saving archive to Downloads/OmniFile...")
    val saved = DownloadStorageHelper.saveToDownloads(
        context = context,
        outputFile = tempZipFile,
        targetFileName = archiveName,
        mimeType = "application/zip"
    )
    saved
}

// --- Split File and Save Directly to Downloads/OmniFile ---
suspend fun splitAndSaveFile(
    context: Context,
    source: FileDetails,
    chunkBytes: Long
): List<com.example.engine.SavedFileInfo> = withContext(Dispatchers.IO) {
    val baseName = source.name
    val buffer = ByteArray(32768)
    var partIndex = 1
    val savedParts = mutableListOf<com.example.engine.SavedFileInfo>()

    context.contentResolver.openInputStream(source.uri)?.use { input ->
        while (true) {
            val partFileName = "${baseName}.part%03d".format(Locale.US, partIndex)
            val tempPartFile = File(context.cacheDir, "temp_split_$partIndex.tmp")
            var writtenThisPart = 0L

            FileOutputStream(tempPartFile).use { out ->
                while (writtenThisPart < chunkBytes) {
                    val toRead = minOf(buffer.size.toLong(), chunkBytes - writtenThisPart).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    writtenThisPart += read
                }
            }

            if (tempPartFile.length() > 0) {
                val savedInfo = DownloadStorageHelper.saveToDownloads(
                    context = context,
                    outputFile = tempPartFile,
                    targetFileName = partFileName,
                    mimeType = "application/octet-stream"
                )
                savedParts.add(savedInfo)
                partIndex++
            } else {
                tempPartFile.delete()
                break
            }

            if (writtenThisPart < chunkBytes) break // EOF reached
        }
    }
    savedParts
}

// Natural sort order extractor for split chunk names like .part001, .part1, .001
fun extractPartIndex(fileName: String): Int {
    val pattern = Regex("part(\\d+)", RegexOption.IGNORE_CASE)
    val match = pattern.find(fileName)
    if (match != null) {
        return match.groupValues[1].toIntOrNull() ?: 0
    }
    val digitSuffix = Regex("\\.(\\d+)$").find(fileName)
    if (digitSuffix != null) {
        return digitSuffix.groupValues[1].toIntOrNull() ?: 0
    }
    return 0
}

// --- Join / Reassemble Split Parts Back into Single Intact File ---
suspend fun mergeAndSaveParts(
    context: Context,
    parts: List<FileDetails>,
    targetFileName: String
): com.example.engine.SavedFileInfo = withContext(Dispatchers.IO) {
    val tempMergedFile = File(context.cacheDir, "temp_merge_${System.currentTimeMillis()}.tmp")
    val buffer = ByteArray(65536)

    // Strictly sort parts in natural numerical sequence (.part001, .part002...)
    val sortedParts = parts.sortedWith(compareBy({ extractPartIndex(it.name) }, { it.name }))

    FileOutputStream(tempMergedFile).use { outStream ->
        sortedParts.forEach { part ->
            context.contentResolver.openInputStream(part.uri)?.use { inStream ->
                var read: Int
                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                }
            }
        }
    }

    val ext = targetFileName.substringAfterLast('.', "").lowercase()
    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

    val saved = DownloadStorageHelper.saveToDownloads(
        context = context,
        outputFile = tempMergedFile,
        targetFileName = targetFileName,
        mimeType = mimeType
    )
    saved
}
