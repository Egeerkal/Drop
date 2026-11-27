package com.videoindirici.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoDownloaderApp() }
    }
}

private enum class MediaTab(val label: String) {
    ALL("Tümü"),
    VIDEO("Videolar"),
    PHOTO("Fotoğraflar"),
    LIVE_PHOTO("Live Photo")
}

private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "m4v")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
private val LIVE_EXTS = setOf("mov", "gif")

private fun getDownloadDir(): File {
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Drop")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isLivePhotoFile(file: File): Boolean {
    val ext = file.extension.lowercase(Locale.ROOT)
    val name = file.name.lowercase(Locale.ROOT)
    return ext in LIVE_EXTS || name.startsWith("live_") || "_live" in name
}

private fun isPhotoFile(file: File): Boolean {
    val ext = file.extension.lowercase(Locale.ROOT)
    return ext in IMAGE_EXTS && !isLivePhotoFile(file)
}

private fun isStandardVideoFile(file: File): Boolean {
    val ext = file.extension.lowercase(Locale.ROOT)
    return ext in VIDEO_EXTS && !isLivePhotoFile(file)
}

private fun isPlayableVideo(file: File): Boolean {
    val ext = file.extension.lowercase(Locale.ROOT)
    return ext in VIDEO_EXTS || ext == "mov"
}

private fun loadDownloadedMedia(): List<File> {
    val allowed = VIDEO_EXTS + IMAGE_EXTS + LIVE_EXTS
    return getDownloadDir().listFiles()
        ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in allowed }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.getDefault(), "%.1f MB", mb)
    else String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}

private fun formatDate(millis: Long): String = SimpleDateFormat("dd MMM · HH:mm", Locale("tr")).format(Date(millis))

private fun httpGetText(urlStr: String): Pair<String, String> {
    var current = urlStr
    var conn: HttpURLConnection
    var redirects = 0
    while (true) {
        conn = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        val code = conn.responseCode
        if (code in 300..399 && redirects < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
            conn.disconnect()
            redirects++
        } else {
            break
        }
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    return current to body
}

private fun downloadDirectFile(urlStr: String, outFile: File, requireVideoMime: Boolean = false): Boolean {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
    }
    return try {
        val ct = (conn.contentType ?: "").lowercase(Locale.ROOT)
        if (requireVideoMime && !ct.startsWith("video/") && "octet-stream" !in ct) {
            false
        } else {
            conn.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.exists() && outFile.length() > 1024
        }
    } catch (_: Exception) {
        outFile.delete()
        false
    } finally {
        conn.disconnect()
    }
}

private fun findJsonObjectByKey(node: Any?, targetKey: String): JSONObject? {
    when (node) {
        is JSONObject -> {
            if (node.has(targetKey)) {
                val candidate = node.optJSONObject(targetKey)
                if (candidate != null && candidate.has("images")) return candidate
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = findJsonObjectByKey(node.opt(keys.next()), targetKey)
                if (found != null) return found
            }
        }
        is JSONArray -> {
            for (i in 0 until node.length()) {
                val found = findJsonObjectByKey(node.opt(i), targetKey)
                if (found != null) return found
            }
        }
    }
    return null
}

private fun extractFirstUrl(obj: JSONObject?, key: String): String? {
    val nested = obj?.optJSONObject(key) ?: return null
    val list = nested.optJSONArray("urlList") ?: nested.optJSONArray("url_list") ?: return null
    for (i in 0 until list.length()) {
        val u = list.optString(i)
        if (u.startsWith("http")) return u
    }
    return null
}

private fun tryDownloadTikTokViaTikWm(rawUrl: String, downloadDir: File): Int {
    val encoded = URLEncoder.encode(rawUrl, "UTF-8")
    val (_, resp) = httpGetText("https://www.tikwm.com/api/?url=$encoded&hd=1")
    val root = JSONObject(resp)
    val data = root.optJSONObject("data") ?: return 0
    val stamp = System.currentTimeMillis()
    var saved = 0

    // 1. Live Photo / video stream check (hdplay or play)
    val videoCandidates = listOf(data.optString("hdplay"), data.optString("play"))
        .filter { it.startsWith("http") }
    for (vUrl in videoCandidates) {
        val out = File(downloadDir, "live_${stamp}.mp4")
        if (downloadDirectFile(vUrl, out, requireVideoMime = true)) {
            saved++
            break
        } else {
            out.delete()
        }
    }

    // 2. Clean slideshow images (no blurred play-button cover)
    val images = data.optJSONArray("images")
    if (images != null) {
        for (i in 0 until images.length()) {
            val imgUrl = images.optString(i)
            if (!imgUrl.startsWith("http")) continue
            val ext = when {
                ".png" in imgUrl.lowercase(Locale.ROOT) -> "png"
                ".webp" in imgUrl.lowercase(Locale.ROOT) -> "webp"
                else -> "jpg"
            }
            val out = File(downloadDir, "drop_${stamp}_${i + 1}.$ext")
            if (downloadDirectFile(imgUrl, out)) saved++ else out.delete()
        }
    }
    return saved
}

private fun tryDownloadPhotosFallback(rawUrl: String, downloadDir: File): Int {
    val lowerRaw = rawUrl.lowercase(Locale.ROOT)
    if ("tiktok.com" in lowerRaw) {
        try {
            val tikwmSaved = tryDownloadTikTokViaTikWm(rawUrl, downloadDir)
            if (tikwmSaved > 0) return tikwmSaved
        } catch (_: Exception) {}
    }

    val (resolvedUrl, body) = httpGetText(rawUrl)
    val lower = resolvedUrl.lowercase(Locale.ROOT)
    val photoUrls = mutableListOf<String>()
    val liveUrls = mutableListOf<String>()

    if ("tiktok.com" in lower && "/photo/" in lower) {
        val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val idx = body.indexOf(marker)
        if (idx != -1) {
            val start = body.indexOf(">", idx) + 1
            val end = body.indexOf("</script>", start)
            if (start > 0 && end > start) {
                val jsonText = body.substring(start, end)
                try {
                    val root = JSONObject(jsonText)
                    val imagePost = findJsonObjectByKey(root, "imagePost")
                    val images = imagePost?.optJSONArray("images")
                    if (images != null) {
                        for (i in 0 until images.length()) {
                            val item = images.optJSONObject(i) ?: continue
                            val liveVideo = item.optJSONObject("video") ?: item.optJSONObject("livePhoto")
                            val liveUrl = extractFirstUrl(liveVideo, "playAddr")
                                ?: liveVideo?.optString("playAddr")?.takeIf { it.startsWith("http") }
                                ?: liveVideo?.optString("downloadAddr")?.takeIf { it.startsWith("http") }
                            if (!liveUrl.isNullOrBlank() && liveUrl !in liveUrls) {
                                liveUrls.add(liveUrl)
                            } else {
                                val imgUrl = extractFirstUrl(item, "imageURL")
                                if (!imgUrl.isNullOrBlank() && imgUrl !in photoUrls) {
                                    photoUrls.add(imgUrl)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    } else if ("reddit.com" in lower) {
        val clean = resolvedUrl.substringBefore("?").trimEnd('/') + ".json"
        val (_, jsonBody) = httpGetText(clean)
        val arr = JSONArray(jsonBody)
        val postData = arr.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data")
        if (postData.has("media_metadata")) {
            val meta = postData.getJSONObject("media_metadata")
            val keys = meta.keys()
            while (keys.hasNext()) {
                val item = meta.getJSONObject(keys.next())
                val imgUrl = item.optJSONObject("s")?.optString("u")?.replace("&amp;", "&")
                val gifUrl = item.optJSONObject("s")?.optString("gif")?.replace("&amp;", "&")
                if (!gifUrl.isNullOrBlank()) liveUrls.add(gifUrl)
                else if (!imgUrl.isNullOrBlank()) photoUrls.add(imgUrl)
            }
        } else {
            val single = postData.optString("url_overridden_by_dest")
            if (single.endsWith(".gif")) liveUrls.add(single)
            else if (single.endsWith(".jpg") || single.endsWith(".jpeg") || single.endsWith(".png") || single.endsWith(".webp")) {
                photoUrls.add(single)
            }
        }
    }

    if (photoUrls.isEmpty() && liveUrls.isEmpty()) return 0
    val stamp = System.currentTimeMillis()
    var saved = 0

    photoUrls.forEachIndexed { i, imgUrl ->
        val ext = when {
            ".png" in imgUrl.lowercase(Locale.ROOT) -> "png"
            ".webp" in imgUrl.lowercase(Locale.ROOT) -> "webp"
            else -> "jpg"
        }
        val out = File(downloadDir, "drop_${stamp}_${i + 1}.$ext")
        if (downloadDirectFile(imgUrl, out)) saved++ else out.delete()
    }

    liveUrls.forEachIndexed { i, liveUrl ->
        val ext = if (".gif" in liveUrl.lowercase(Locale.ROOT)) "gif" else "mp4"
        val out = File(downloadDir, "live_${stamp}_${i + 1}.$ext")
        if (downloadDirectFile(liveUrl, out)) saved++ else out.delete()
    }
    return saved
}

@Composable
fun VideoDownloaderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLibrary by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var progressValue by remember { mutableStateOf<Int?>(null) }
    var statusText by remember { mutableStateOf("Linki kopyalayıp daireye dokunun") }
    var isError by remember { mutableStateOf(false) }
    var mediaFiles by remember { mutableStateOf(loadDownloadedMedia()) }

    fun refreshMedia() { mediaFiles = loadDownloadedMedia() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            } catch (_: Exception) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { isError = true; statusText = "İndirme için depolama izni gerekiyor." }
    }

    fun readClipboardUrl(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        return Regex("https?://[^\\s]+").find(clipText)?.value
    }

    fun startDownloadFromClipboard() {
        if (isDownloading) return
        val url = readClipboardUrl()
        if (url.isNullOrBlank()) { isError = true; statusText = "Panoda geçerli bir bağlantı bulunamadı."; return }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        isDownloading = true; progressValue = null; isError = false; statusText = "En yüksek kalite hazırlanıyor..."
        scope.launch {
            try {
                val photoCount = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    val downloadDir = getDownloadDir()

                    var photosSaved = 0
                    try {
                        val request = YoutubeDLRequest(url).apply {
                            addOption("--no-update")
                            addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best")
                            addOption("--merge-output-format", "mp4")
                            addOption("--no-playlist")
                            addOption("-o", File(downloadDir, "%(title).80s.%(ext)s").absolutePath)
                        }
                        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                            scope.launch(Dispatchers.Main) {
                                if (progress >= 0f) { progressValue = progress.toInt(); statusText = "İndiriliyor..." }
                                else { statusText = "En yüksek kalite işleniyor..." }
                            }
                        }
                    } catch (ytErr: Exception) {
                        photosSaved = tryDownloadPhotosFallback(url, downloadDir)
                        if (photosSaved == 0) throw ytErr
                    }

                    val files = downloadDir.listFiles()?.map { it.absolutePath }?.toTypedArray()
                    if (!files.isNullOrEmpty()) MediaScannerConnection.scanFile(context, files, null, null)
                    photosSaved
                }
                refreshMedia(); isError = false; progressValue = null
                statusText = if (photoCount > 0) "$photoCount dosya kaydedildi ✓" else "Kaydedildi · Medya bölümünden açabilirsiniz"
            } catch (e: Exception) {
                isError = true; progressValue = null; statusText = "İndirilemedi: ${e.localizedMessage ?: "Desteklenmeyen bağlantı"}"
            } finally { isDownloading = false }
        }
    }

    fun openMedia(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = if (isPlayableVideo(file)) "video/*" else "image/*"
            context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        } catch (_: Exception) { isError = true; statusText = "Dosya açılamadı." }
    }

    fun shareMedia(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = if (isPlayableVideo(file)) "video/*" else "image/*"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Paylaş"))
        } catch (_: Exception) {}
    }

    fun deleteMedia(file: File) { if (file.exists()) { file.delete(); refreshMedia() } }

    BackHandler(enabled = showLibrary) { showLibrary = false }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF6F7FA)))).statusBarsPadding()) {
        AnimatedContent(targetState = showLibrary, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) }, label = "switch") { inLib ->
            if (!inLib) HomeScreen(isDownloading, progressValue, statusText, isError, mediaFiles.size, { startDownloadFromClipboard() }, { refreshMedia(); showLibrary = true })
            else LibraryScreen(mediaFiles, { showLibrary = false }, { openMedia(it) }, { shareMedia(it) }, { deleteMedia(it) })
        }
    }
}

@Composable
private fun HomeScreen(isDownloading: Boolean, progressValue: Int?, statusText: String, isError: Boolean, mediaCount: Int, onOrbClick: () -> Unit, onOpenLibrary: () -> Unit) {
    val pulseScale by rememberInfiniteTransition(label = "p").animateFloat(
        initialValue = 1f, targetValue = 1.045f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "s"
    )
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 36.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "DROP", fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 5.sp, color = Color(0xFF8C929C))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(184.dp).scale(if (isDownloading) pulseScale else 1f)
                    .shadow(24.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1A000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !isDownloading, onClick = onOrbClick),
                shape = CircleShape, color = Color(0xFFFCFCFD), border = BorderStroke(1.5.dp, Color(0xFFE7E9EF))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isDownloading && progressValue != null -> Text(text = "%$progressValue", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color(0xFF111111))
                        isDownloading -> Text(text = "•••", fontSize = 26.sp, fontWeight = FontWeight.Light, color = Color(0xFF111111))
                        else -> DownloadArrowIcon(modifier = Modifier.size(34.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(text = statusText, fontSize = 13.sp, color = if (isError) Color(0xFFD93025) else Color(0xFF7D838E), textAlign = TextAlign.Center, lineHeight = 19.sp)
        }
        Surface(
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenLibrary),
            shape = RoundedCornerShape(50), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE9ECF2)), shadowElevation = 4.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GridDotsIcon(modifier = Modifier.size(16.dp))
                Text(text = if (mediaCount > 0) "MEDYA · $mediaCount" else "MEDYA", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp, color = Color(0xFF181A1F))
            }
        }
    }
}

@Composable
private fun LibraryScreen(files: List<File>, onBack: () -> Unit, onPlay: (File) -> Unit, onShare: (File) -> Unit, onDelete: (File) -> Unit) {
    var selectedTab by remember { mutableStateOf(MediaTab.ALL) }
    var pendingDeleteFile by remember { mutableStateOf<File?>(null) }

    val videoFiles = remember(files) { files.filter { isStandardVideoFile(it) } }
    val photoFiles = remember(files) { files.filter { isPhotoFile(it) } }
    val liveFiles = remember(files) { files.filter { isLivePhotoFile(it) } }

    val filteredFiles = when (selectedTab) {
        MediaTab.ALL -> files
        MediaTab.VIDEO -> videoFiles
        MediaTab.PHOTO -> photoFiles
        MediaTab.LIVE_PHOTO -> liveFiles
    }

    val counts = mapOf(
        MediaTab.ALL to files.size,
        MediaTab.VIDEO to videoFiles.size,
        MediaTab.PHOTO to photoFiles.size,
        MediaTab.LIVE_PHOTO to liveFiles.size
    )

    pendingDeleteFile?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    text = "Medyayı Kaldır",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF14161A)
                )
            },
            text = {
                Text(
                    text = "Bu medyayı kaldırmaktan emin misiniz?",
                    fontSize = 14.sp,
                    color = Color(0xFF5F6570)
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) {
                    Text(
                        text = "Vazgeç",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF7D838E)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(fileToDelete)
                    pendingDeleteFile = null
                }) {
                    Text(
                        text = "Kaldır",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD93025)
                    )
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, Color(0xFFE9ECF2))) {
                Box(contentAlignment = Alignment.Center) { Text(text = "←", fontSize = 18.sp, color = Color(0xFF111111)) }
            }
            Text(text = "MEDYA", fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp, color = Color(0xFF181A1F))
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(text = "${filteredFiles.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8C929C))
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(MediaTab.entries) { tab ->
                val active = tab == selectedTab
                val count = counts[tab] ?: 0
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { selectedTab = tab },
                    shape = RoundedCornerShape(50),
                    color = if (active) Color(0xFF14161A) else Color.White,
                    border = if (active) null else BorderStroke(1.dp, Color(0xFFECEFF4)),
                    shadowElevation = if (active) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (active) Color.White else Color(0xFF3A3E47)
                        )
                        Text(
                            text = "$count",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (active) Color(0xFFB8BDC7) else Color(0xFF9AA0AA)
                        )
                    }
                }
            }
        }

        if (filteredFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val emptyTitle = when (selectedTab) {
                        MediaTab.ALL -> "Henüz medya yok"
                        MediaTab.VIDEO -> "Henüz video yok"
                        MediaTab.PHOTO -> "Henüz fotoğraf yok"
                        MediaTab.LIVE_PHOTO -> "Henüz Live Photo yok"
                    }
                    Text(text = emptyTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2B2E36))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "İndirdiğiniz içerikler burada görünecek", fontSize = 13.sp, color = Color(0xFF8C929C))
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredFiles, key = { it.absolutePath }) { file ->
                    MediaItemCard(file, { onPlay(file) }, { onShare(file) }, { pendingDeleteFile = file })
                }
            }
        }
    }
}

@Composable
private fun MediaItemCard(file: File, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val isPlayable = isPlayableVideo(file)
    val isLive = isLivePhotoFile(file)
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        thumbnail = withContext(Dispatchers.IO) {
            if (isPlayable) {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(file.absolutePath); r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                catch (_: Exception) { null }
                finally { try { r.release() } catch (_: Exception) {} }
            } else {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                } catch (_: Exception) { null }
            }
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onPlay), shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFECEFF4)), shadowElevation = 2.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F3F6)), contentAlignment = Alignment.Center) {
                thumbnail?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (isLive) {
                    Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(Color(0xCCFFFFFF)), contentAlignment = Alignment.Center) { Text(text = "◎", fontSize = 12.sp, color = Color(0xFF111111)) }
                } else if (isPlayable) {
                    Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(Color(0xB3FFFFFF)), contentAlignment = Alignment.Center) { Text(text = "▶", fontSize = 10.sp, color = Color(0xFF111111)) }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = file.nameWithoutExtension, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF14161A), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${formatFileSize(file.length())} · ${formatDate(file.lastModified())}", fontSize = 12.sp, color = Color(0xFF8C929C))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallActionChip("↗", onShare)
                SmallActionChip("×", onDelete, isDanger = true)
            }
        }
    }
}

@Composable
private fun SmallActionChip(label: String, onClick: () -> Unit, isDanger: Boolean = false) {
    Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFF6F7FA)).border(1.dp, Color(0xFFECEFF4), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text = label, fontSize = 15.sp, color = if (isDanger) Color(0xFFD93025) else Color(0xFF181A1F))
    }
}

@Composable
private fun DownloadArrowIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = Color(0xFF111111)
        drawLine(color = color, start = Offset(w * 0.5f, h * 0.16f), end = Offset(w * 0.5f, h * 0.68f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        val chevron = Path().apply { moveTo(w * 0.28f, h * 0.48f); lineTo(w * 0.5f, h * 0.70f); lineTo(w * 0.72f, h * 0.48f) }
        drawPath(path = chevron, color = color, style = stroke)
        drawLine(color = color, start = Offset(w * 0.22f, h * 0.84f), end = Offset(w * 0.78f, h * 0.84f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
private fun GridDotsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Color(0xFF181A1F); val r = size.width * 0.1f
        for (px in listOf(0.2f, 0.5f, 0.8f)) for (py in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color = color, radius = r, center = Offset(size.width * px, size.height * py))
    }
}
