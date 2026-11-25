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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoDownloaderApp() }
    }
}

private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

private fun getDownloadDir(): File {
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Drop")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isVideoFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in VIDEO_EXTS

private fun loadDownloadedMedia(): List<File> {
    val allowed = VIDEO_EXTS + IMAGE_EXTS
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

private fun downloadDirectFile(urlStr: String, outFile: File) {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
    }
    conn.inputStream.use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
    conn.disconnect()
}

private fun tryDownloadPhotosFallback(rawUrl: String, downloadDir: File): Int {
    val (resolvedUrl, body) = httpGetText(rawUrl)
    val lower = resolvedUrl.lowercase(Locale.ROOT)
    val urls = mutableListOf<String>()

    if ("tiktok.com" in lower && "/photo/" in lower) {
        val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val idx = body.indexOf(marker)
        if (idx != -1) {
            val start = body.indexOf(">", idx) + 1
            val end = body.indexOf("</script>", start)
            if (start > 0 && end > start) {
                val jsonText = body.substring(start, end)
                val regex = Regex(""""urlList"\s*:\s*\[\s*"(https:[^"]+)"""")
                for (m in regex.findAll(jsonText)) {
                    val u = m.groupValues[1].replace("\\u002F", "/").replace("\\/", "/")
                    if (("photomode" in u || "tplv-" in u) && u !in urls) {
                        urls.add(u)
                    }
                }
            }
        }
    } else if ("reddit.com" in lower) {
        val clean = resolvedUrl.substringBefore("?").trimEnd('/') + ".json"
        val (_, jsonBody) = httpGetText(clean)
        val arr = org.json.JSONArray(jsonBody)
        val postData = arr.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data")
        if (postData.has("media_metadata")) {
            val meta = postData.getJSONObject("media_metadata")
            val keys = meta.keys()
            while (keys.hasNext()) {
                val item = meta.getJSONObject(keys.next())
                val imgUrl = item.optJSONObject("s")?.optString("u")?.replace("&amp;", "&")
                if (!imgUrl.isNullOrBlank()) urls.add(imgUrl)
            }
        } else {
            val single = postData.optString("url_overridden_by_dest")
            if (single.endsWith(".jpg") || single.endsWith(".jpeg") || single.endsWith(".png") || single.endsWith(".webp")) {
                urls.add(single)
            }
        }
    }

    if (urls.isEmpty()) return 0
    val stamp = System.currentTimeMillis()
    var saved = 0
    urls.forEachIndexed { i, imgUrl ->
        val ext = when {
            ".png" in imgUrl.lowercase(Locale.ROOT) -> "png"
            ".webp" in imgUrl.lowercase(Locale.ROOT) -> "webp"
            else -> "jpg"
        }
        val out = File(downloadDir, "drop_${stamp}_${i + 1}.$ext")
        try {
            downloadDirectFile(imgUrl, out)
            if (out.exists() && out.length() > 1024) saved++ else out.delete()
        } catch (_: Exception) {
            out.delete()
        }
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
                statusText = if (photoCount > 0) "$photoCount fotoğraf kaydedildi ✓" else "Kaydedildi · Medya bölümünden açabilirsiniz"
            } catch (e: Exception) {
                isError = true; progressValue = null; statusText = "İndirilemedi: ${e.localizedMessage ?: "Desteklenmeyen bağlantı"}"
            } finally { isDownloading = false }
        }
    }

    fun openMedia(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = if (isVideoFile(file)) "video/*" else "image/*"
            context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        } catch (_: Exception) { isError = true; statusText = "Dosya açılamadı." }
    }

    fun shareMedia(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = if (isVideoFile(file)) "video/*" else "image/*"
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
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, Color(0xFFE9ECF2))) {
                Box(contentAlignment = Alignment.Center) { Text(text = "←", fontSize = 18.sp, color = Color(0xFF111111)) }
            }
            Text(text = "MEDYA", fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp, color = Color(0xFF181A1F))
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { Text(text = "${files.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8C929C)) }
        }
        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Henüz dosya yok", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2B2E36))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "İndirdiğiniz video ve fotoğraflar burada görünecek", fontSize = 13.sp, color = Color(0xFF8C929C))
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(files, key = { it.absolutePath }) { file -> MediaItemCard(file, { onPlay(file) }, { onShare(file) }, { onDelete(file) }) }
            }
        }
    }
}

@Composable
private fun MediaItemCard(file: File, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val isVid = isVideoFile(file)
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        thumbnail = withContext(Dispatchers.IO) {
            if (isVid) {
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
                if (isVid) {
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
