package com.instadownloader.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Downloads", "Settings")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Default.Home
                                    1 -> Icons.Default.Download
                                    else -> Icons.Default.Settings
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> DownloadsScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun HomeScreen() {
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var mediaItems by remember { mutableStateOf<List<MediaResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Instagram Downloader",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Text(
            "Download photos, videos and carousels in a few taps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Paste Instagram URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        url = clip.getItemAt(0).text?.toString() ?: ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Paste") }

            OutlinedButton(
                onClick = { url = ""; mediaItems = emptyList(); error = null },
                modifier = Modifier.weight(1f)
            ) { Text("Clear") }
        }

        Button(
            onClick = {
                if (url.isBlank() || !url.contains("instagram.com") && !url.contains("instagr.am")) {
                    error = "Please enter a valid Instagram URL"
                    return@Button
                }
                isLoading = true
                error = null
                scope.launch {
                    try {
                        val items = withContext(Dispatchers.IO) {
                            InstagramDownloader.getMediaItems(url)
                        }
                        mediaItems = items
                        if (items.isEmpty()) error = "No media found. Post may be private, deleted, or login-restricted."
                    } catch (e: Exception) {
                        error = when {
                            e.message?.contains("private", true) == true -> "This post is private or requires login."
                            e.message?.contains("404") == true || e.message?.contains("not found", true) == true -> "Post not found or deleted."
                            else -> "Failed to retrieve media: ${e.message ?: "Unknown error"}"
                        }
                        mediaItems = emptyList()
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Fetching...")
            } else {
                Text("Get Media")
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }

        if (mediaItems.size > 1) {
            var allDownloading by remember { mutableStateOf(false) }
            var allStatus by remember { mutableStateOf("") }
            Button(
                onClick = {
                    allDownloading = true
                    allStatus = "Preparing ZIP..."
                    scope.launch {
                        try {
                            val success = withContext(Dispatchers.IO) {
                                downloadAllAsZip(context, mediaItems)
                            }
                            allStatus = if (success) "All media saved as ZIP" else "ZIP download failed"
                        } catch (e: Exception) {
                            allStatus = "Error: ${e.message}"
                        } finally {
                            allDownloading = false
                        }
                    }
                },
                enabled = !allDownloading,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                if (allDownloading) CircularProgressIndicator(Modifier.size(16.dp))
                else Text("Download All as ZIP")
            }
            if (allStatus.isNotEmpty()) Text(allStatus, style = MaterialTheme.typography.bodySmall)
        }

        if (mediaItems.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(mediaItems) { item ->
                    MediaCard(item, context)
                }
            }
        }
    }
}

@Composable
fun MediaCard(item: MediaResult, context: Context) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (item.isVideo) "Video / Reel" else "Photo",
                style = MaterialTheme.typography.titleMedium
            )
            Text("Size: ${item.width}x${item.height}", style = MaterialTheme.typography.bodySmall)
            if (item.isVideo && item.durationSec > 0) {
                Text("Duration: ${item.durationSec.toInt()}s", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = {
                    downloading = true
                    status = "Downloading..."
                    progress = 0.3f
                    scope.launch {
                        try {
                            val success = withContext(Dispatchers.IO) {
                                downloadMedia(context, item) { p -> progress = p }
                            }
                            status = if (success) "Saved successfully" else "Download failed — tap Retry"
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        } finally {
                            downloading = false
                            progress = 1f
                        }
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (status.contains("failed")) "Retry Download" else "Download ${if (item.isVideo) "Video" else "Photo"}")
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    var history by remember { mutableStateOf(DownloadHistory.load(context)) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = {
                DownloadHistory.clear(context)
                history = emptyList()
            }) { Text("Clear History") }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(64.dp))
                    Text("No downloads yet", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Text("Saved files appear in your Downloads folder", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn {
                items(history) { record ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(record.filename, style = MaterialTheme.typography.titleSmall)
                            Text("${record.type.uppercase()} • ${DownloadHistory.formatSize(record.size)} • ${DownloadHistory.formatDate(record.date)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
        Text("Download location: Public Downloads folder", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Text("App version: 1.0.0", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Disclaimer: This app retrieves only publicly accessible Instagram media. Private accounts, login-required content, and deleted posts are not supported. Respect creators’ rights and Instagram’s terms of service. Not affiliated with Instagram/Meta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

private fun downloadMedia(context: Context, item: MediaResult, onProgress: (Float) -> Unit = {}): Boolean {
    return try {
        onProgress(0.2f)
        val client = OkHttpClient()
        val request = Request.Builder().url(item.url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return false
        val bytes = response.body?.bytes() ?: return false
        onProgress(0.6f)

        val ext = if (item.isVideo) "mp4" else "jpg"
        val filename = "IG_${System.currentTimeMillis()}.$ext"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (item.isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (item.isVideo) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, contentValues) ?: return false

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        onProgress(0.9f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        DownloadHistory.add(context, DownloadRecord(
            filename = filename,
            type = if (item.isVideo) "video" else "photo",
            date = System.currentTimeMillis(),
            size = bytes.size.toLong(),
            uri = uri.toString()
        ))
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun downloadAllAsZip(context: Context, items: List<MediaResult>): Boolean {
    return try {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            items.forEachIndexed { index, item ->
                val client = OkHttpClient()
                val resp = client.newCall(Request.Builder().url(item.url).build()).execute()
                if (!resp.isSuccessful) return@forEachIndexed
                val bytes = resp.body?.bytes() ?: return@forEachIndexed
                val ext = if (item.isVideo) "mp4" else "jpg"
                val entryName = "item_${index + 1}.$ext"
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        val zipBytes = baos.toByteArray()
        if (zipBytes.isEmpty()) return false

        val filename = "IG_Carousel_${System.currentTimeMillis()}.zip"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(zipBytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        DownloadHistory.add(context, DownloadRecord(
            filename = filename,
            type = "zip",
            date = System.currentTimeMillis(),
            size = zipBytes.size.toLong(),
            uri = uri.toString()
        ))
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
