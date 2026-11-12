package com.example.ui.screens

import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.DownloadStorageHelper
import com.example.engine.FileInspector
import com.example.model.FileDetails
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun BatchAndToolsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val accentSuccess = Color(0xFF10B981)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = remember { listOf("Bundle ZIP", "Splitter", "Join Chunks", "Data Matrix") }

    // Tool 0: Multi-File Zip State
    var selectedBatchDetails by remember { mutableStateOf<List<FileDetails>>(emptyList()) }
    var archiveName by remember { mutableStateOf("Archive_${System.currentTimeMillis() % 10000}") }
    var zipCompressionLevel by remember { mutableIntStateOf(9) }
    var batchZipStatus by remember { mutableStateOf<String?>(null) }
    var isZipping by remember { mutableStateOf(false) }
    var zipProgress by remember { mutableStateOf(0f) }
    var createdZipInfo by remember { mutableStateOf<com.example.engine.SavedFileInfo?>(null) }

    val batchFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
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
    var lastSplitParts by remember { mutableStateOf<List<com.example.engine.SavedFileInfo>>(emptyList()) }

    val splitterFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            splitterFile = FileInspector.inspectUri(context, it)
            splitStatus = null
            lastSplitParts = emptyList()
        }
    }

    // Tool 2: Join / Merge Parts State
    var joinDetails by remember { mutableStateOf<List<FileDetails>>(emptyList()) }
    var joinStatus by remember { mutableStateOf<String?>(null) }
    var isJoining by remember { mutableStateOf(false) }
    var mergedResultInfo by remember { mutableStateOf<com.example.engine.SavedFileInfo?>(null) }
    var customMergedName by remember { mutableStateOf("") }

    val joinFilesPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            joinStatus = null
            mergedResultInfo = null
            scope.launch {
                joinDetails = withContext(Dispatchers.IO) {
                    uris.map { FileInspector.inspectUri(context, it) }.sortedBy { it.name }
                }
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tab Selector with native for-loop
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            for (idx in tabTitles.indices) {
                val title = tabTitles[idx]
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
            // TAB 0: BUNDLE ZIP
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bundle Files into ZIP", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { batchFilePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().testTag("select_files_to_zip_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (selectedBatchDetails.isEmpty()) "Select Files to Bundle"
                                else "${selectedBatchDetails.size} Files Selected (${FileDetails.formatBytes(selectedBatchDetails.sumOf { it.size })})"
                            )
                        }

                        if (selectedBatchDetails.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = archiveName,
                                onValueChange = { archiveName = it.replace(Regex("[^a-zA-Z0-9_\\-]"), "") },
                                label = { Text("Archive Name") },
                                suffix = { Text(".zip") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Compression Level:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val levels = listOf(Pair("Fast (1)", 1), Pair("Normal (5)", 5), Pair("Ultra L9 (9)", 9))
                                for ((lbl, lvl) in levels) {
                                    FilterChip(
                                        selected = zipCompressionLevel == lvl,
                                        onClick = { zipCompressionLevel = lvl },
                                        label = { Text(lbl, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (isZipping) {
                                LinearProgressIndicator(
                                    progress = zipProgress,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(batchZipStatus ?: "Compressing...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isZipping = true
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
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("create_zip_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Create ZIP Archive")
                                }
                            }
                        }

                        if (createdZipInfo != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = accentSuccess.copy(alpha = 0.12f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Archive Created & Saved to Downloads!", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accentSuccess)
                                    Text("${createdZipInfo!!.fileName} (${FileDetails.formatBytes(createdZipInfo!!.sizeBytes)})", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.shareFile(context, createdZipInfo!!.absolutePath) }, shape = RoundedCornerShape(8.dp)) {
                                            Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Share ZIP", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = { viewModel.openDownloadsFolder(context) }, shape = RoundedCornerShape(8.dp)) {
                                            Text("Downloads", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 1: SPLITTER
            1 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CallSplit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Split Large File", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        if (splitterFile == null) {
                            OutlinedButton(
                                onClick = { splitterFilePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth().testTag("select_split_file_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select File to Split")
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(splitterFile!!.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text(splitterFile!!.formattedSize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = { splitterFilePicker.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(8.dp)) {
                                        Text("Change", fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = splitChunkMb,
                                onValueChange = { splitChunkMb = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Chunk Size per Part (MB)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        isSplitting = true
                                        splitStatus = "Splitting into exact parts..."
                                        val chunkBytes = ((splitChunkMb.toDoubleOrNull() ?: 1.0) * 1024 * 1024).toLong().coerceAtLeast(1024L)
                                        val parts = splitAndSaveFile(context, splitterFile!!, chunkBytes)
                                        lastSplitParts = parts
                                        isSplitting = false
                                        splitStatus = "Generated ${parts.size} parts in Downloads/OmniFile!"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("split_now_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Split File Into Parts")
                            }
                        }

                        if (splitStatus != null && lastSplitParts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = accentSuccess.copy(alpha = 0.12f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(splitStatus!!, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = accentSuccess)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val details = withContext(Dispatchers.IO) {
                                                        lastSplitParts.mapNotNull { p ->
                                                            p.contentUri?.let { uri -> FileInspector.inspectUri(context, uri) }
                                                        }
                                                    }
                                                    if (details.isNotEmpty()) {
                                                        joinDetails = details
                                                        customMergedName = splitterFile?.name ?: "restored_file"
                                                    }
                                                    selectedTabIndex = 2
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Text("Transfer to Joiner", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = { viewModel.openDownloadsFolder(context) }, shape = RoundedCornerShape(8.dp)) {
                                            Text("Downloads", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: JOIN CHUNKS
            2 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MergeType, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reassemble Split Parts", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { joinFilesPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().testTag("select_parts_to_join_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (joinDetails.isEmpty()) "Select Split Parts to Merge"
                                else "${joinDetails.size} Parts Selected (${FileDetails.formatBytes(joinDetails.sumOf { it.size })})"
                            )
                        }

                        if (joinDetails.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val firstPartName = joinDetails.first().name
                            val detectedOriginalName = if (customMergedName.isNotEmpty()) customMergedName
                            else if (firstPartName.contains(".part")) firstPartName.substringBefore(".part")
                            else "restored_file"

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("TARGET RESTORATION FILE:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    Text(detectedOriginalName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isJoining) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(joinStatus ?: "Merging streams...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isJoining = true
                                            val result = mergeAndSaveParts(context, joinDetails, detectedOriginalName)
                                            mergedResultInfo = result
                                            isJoining = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("merge_now_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Reassemble & Merge Chunks")
                                }
                            }
                        }

                        if (mergedResultInfo != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = accentSuccess.copy(alpha = 0.15f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Original File Restored Successfully!", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = accentSuccess)
                                    Text("${mergedResultInfo!!.fileName} (${FileDetails.formatBytes(mergedResultInfo!!.sizeBytes)})", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.showPreview(mergedResultInfo!!.absolutePath) }, shape = RoundedCornerShape(8.dp)) {
                                            Text("Preview", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = { viewModel.openFile(context, mergedResultInfo!!.absolutePath) }, shape = RoundedCornerShape(8.dp)) {
                                            Text("Open", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = { viewModel.shareFile(context, mergedResultInfo!!.absolutePath) }, shape = RoundedCornerShape(8.dp)) {
                                            Text("Share", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: DATA MATRIX
            3 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Base64 & Hex Matrix", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = rawInputText,
                            onValueChange = { rawInputText = it },
                            label = { Text("Input Content") },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            maxLines = 4
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val modes = listOf("Encode Base64", "Decode Base64", "Hex Dump", "Hex to Text")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in modes) {
                                FilterChip(
                                    selected = transformMode == m,
                                    onClick = {
                                        transformMode = m
                                        transformedOutput = when (m) {
                                            "Encode Base64" -> Base64.encodeToString(rawInputText.toByteArray(), Base64.DEFAULT)
                                            "Decode Base64" -> try {
                                                String(Base64.decode(rawInputText.trim(), Base64.DEFAULT))
                                            } catch (e: Exception) { "Invalid Base64: ${e.message}" }
                                            "Hex Dump" -> rawInputText.toByteArray().joinToString(" ") { String.format(Locale.US, "%02X", it) }
                                            else -> try {
                                                val bytes = rawInputText.trim().split("\\s+".toRegex()).map { it.toInt(16).toByte() }.toByteArray()
                                                String(bytes)
                                            } catch (e: Exception) { "Invalid Hex: ${e.message}" }
                                        }
                                    },
                                    label = { Text(m, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        if (transformedOutput.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("OUTPUT ($transformMode):", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(transformedOutput))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Text(transformedOutput, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helpers
suspend fun createAndSaveBatchZip(
    context: Context,
    files: List<FileDetails>,
    archiveName: String,
    compressionLevel: Int,
    onProgress: (Float, String) -> Unit
): com.example.engine.SavedFileInfo = withContext(Dispatchers.IO) {
    val tempZipFile = File(context.cacheDir, "temp_pack_${System.currentTimeMillis()}.zip")

    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile), 32768)).use { zos ->
        zos.setLevel(compressionLevel)
        val buf = ByteArray(32768)

        for (idx in files.indices) {
            val f = files[idx]
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
    DownloadStorageHelper.saveToDownloads(
        context = context,
        outputFile = tempZipFile,
        targetFileName = archiveName,
        mimeType = "application/zip"
    )
}

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

            if (writtenThisPart < chunkBytes) break
        }
    }
    savedParts
}

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

suspend fun mergeAndSaveParts(
    context: Context,
    parts: List<FileDetails>,
    targetFileName: String
): com.example.engine.SavedFileInfo = withContext(Dispatchers.IO) {
    val tempMergedFile = File(context.cacheDir, "temp_merge_${System.currentTimeMillis()}.tmp")
    val buffer = ByteArray(65536)

    val sortedParts = parts.sortedWith(compareBy({ extractPartIndex(it.name) }, { it.name }))

    FileOutputStream(tempMergedFile).use { outStream ->
        for (part in sortedParts) {
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

    DownloadStorageHelper.saveToDownloads(
        context = context,
        outputFile = tempMergedFile,
        targetFileName = targetFileName,
        mimeType = mimeType
    )
}