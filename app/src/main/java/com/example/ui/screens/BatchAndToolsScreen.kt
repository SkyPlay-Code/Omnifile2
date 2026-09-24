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
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.FileInspector
import com.example.model.FileDetails
import com.example.ui.MainViewModel
import com.example.ui.theme.EmeraldSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
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

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("File Splitter", "Base64 & Hex", "Multi-Zip Pack")

    // Tool 1: Splitter State
    var splitterFile by remember { mutableStateOf<FileDetails?>(null) }
    var splitChunkMb by remember { mutableStateOf("10") }
    var splitStatus by remember { mutableStateOf<String?>(null) }
    var generatedParts by remember { mutableStateOf<List<File>>(emptyList()) }

    val splitterFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            splitterFile = FileInspector.inspectUri(context, it)
            splitStatus = null
            generatedParts = emptyList()
        }
    }

    // Tool 2: Base64 / Hex State
    var rawInputText by remember { mutableStateOf("OmniFile Universal Engine: High performance local processing!") }
    var transformedOutput by remember { mutableStateOf("") }
    var transformMode by remember { mutableStateOf("Encode Base64") }

    // Tool 3: Multi-File Zip State
    var selectedBatchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var batchZipStatus by remember { mutableStateOf<String?>(null) }
    var createdZipFile by remember { mutableStateOf<File?>(null) }

    val batchFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedBatchUris = uris
        batchZipStatus = null
        createdZipFile = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Selector
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            tabTitles.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTabIndex == idx,
                    onClick = { selectedTabIndex = idx },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTabIndex == idx) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedTabIndex) {
            // TAB 0: TARGET CHUNK SPLITTER
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
                            text = "Split any file into exact equal part chunks so it can fit inside strict limits (e.g. Discord 8MB, Gmail 25MB, FAT32 4GB).",
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
                                        splitStatus = "Splitting into exact parts..."
                                        val parts = splitFile(context, splitterFile!!, chunkBytes)
                                        generatedParts = parts
                                        splitStatus = "Created ${parts.size} chunks successfully!"
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
                            Text(
                                text = splitStatus!!,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldSuccess
                            )
                        }

                        if (generatedParts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                generatedParts.take(8).forEach { part ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = part.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        Text(
                                            text = FileDetails.formatBytes(part.length()),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 1: BASE64 & HEX MATRIX
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

            // TAB 2: MULTI-FILE ZIP PACKER
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
                                imageVector = Icons.Default.FolderZip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Multi-File Ultra ZIP Packer",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = "Bundle and compress multiple files simultaneously with Level 9 Deflate compression.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { batchFilePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.UploadFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (selectedBatchUris.isEmpty()) "Select Files to Bundle" else "Selected ${selectedBatchUris.size} Files")
                        }

                        if (selectedBatchUris.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        batchZipStatus = "Packing and compressing ${selectedBatchUris.size} files..."
                                        val zip = createBatchZip(context, selectedBatchUris)
                                        createdZipFile = zip
                                        batchZipStatus = "ZIP created: ${zip.name} (${FileDetails.formatBytes(zip.length())})"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Pack Into High-Ratio ZIP")
                            }
                        }

                        if (batchZipStatus != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = batchZipStatus!!,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldSuccess
                            )
                        }

                        if (createdZipFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.openFile(context, createdZipFile!!.absolutePath) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open ZIP")
                                }
                                Button(
                                    onClick = { viewModel.shareFile(context, createdZipFile!!.absolutePath) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Share ZIP")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun splitFile(context: Context, source: FileDetails, chunkBytes: Long): List<File> = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "split_parts").apply { mkdirs() }
    val baseName = source.name.substringBeforeLast('.')
    val parts = mutableListOf<File>()

    context.contentResolver.openInputStream(source.uri)?.use { input ->
        val buffer = ByteArray(32768)
        var partIndex = 1

        while (true) {
            val partFile = File(dir, "${baseName}.part%03d".format(Locale.US, partIndex))
            var writtenThisPart = 0L
            FileOutputStream(partFile).use { out ->
                while (writtenThisPart < chunkBytes) {
                    val toRead = minOf(buffer.size.toLong(), chunkBytes - writtenThisPart).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    writtenThisPart += read
                }
            }
            if (partFile.length() > 0) {
                parts.add(partFile)
                partIndex++
            } else {
                partFile.delete()
                break
            }
            if (writtenThisPart < chunkBytes) break // EOF reached
        }
    }
    parts
}

suspend fun createBatchZip(context: Context, uris: List<Uri>): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "batch_archives").apply { mkdirs() }
    val zipFile = File(dir, "bundle_${System.currentTimeMillis()}.zip")

    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        zos.setLevel(9)
        val buf = ByteArray(32768)
        uris.forEachIndexed { idx, uri ->
            val details = FileInspector.inspectUri(context, uri)
            val name = if (details.name.isNotBlank()) details.name else "file_$idx"
            val entry = ZipEntry(name)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(uri)?.use { input ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    zos.write(buf, 0, read)
                }
            }
            zos.closeEntry()
        }
    }
    zipFile
}
