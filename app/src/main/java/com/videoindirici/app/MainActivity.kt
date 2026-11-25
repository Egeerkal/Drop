package com.videoindirici.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoDownloaderApp()
        }
    }
}

private fun getDownloadDir(): File {
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Drop"
    )
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun loadDownloadedVideos(): List<File> {
    val dir = getDownloadDir()
    val allowed = setOf("mp4", "mkv", "webm", "mov", "m4v")
    return dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in allowed }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.getDefault(), "%.1f MB", mb)
    else String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("dd MMM · HH:mm", Locale("tr"))
    return fmt.format(Date(millis))
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
    var videos by remember { mutableStateOf(loadDownloadedVideos()) }

    fun refreshVideos() {
        videos = loadDownloadedVideos()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            isError = true
            statusText = "İndirme için depolama izni gerekiyor."
        }
    }

    fun readClipboardUrl(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        val regex = Regex("https?://[^\\s]+")
        return regex.find(clipText)?.value
    }

    fun startDownloadFromClipboard() {
        if (isDownloading) return

        val url = readClipboardUrl()
        if (url.isNullOrBlank()) {
            isError = true
            statusText = "Panoda geçerli bir bağlantı bulunamadı."
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        isDownloading = true
        progressValue = null
        isError = false
        statusText = "En yüksek kalite hazırlanıyor..."

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)

                    val downloadDir = getDownloadDir()
                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best")
                        addOption("--merge-output-format", "mp4")
                        addOption("--no-playlist")
                        addOption("-o", File(downloadDir, "%(title).80s.%(ext)s").absolutePath)
                    }

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        scope.launch(Dispatchers.Main) {
                            if (progress >= 0f) {
                                progressValue = progress.toInt()
                                statusText = "İndiriliyor..."
                            } else {
                                statusText = "En yüksek kalite işleniyor..."
                            }
                        }
                    }

                    val files = downloadDir.listFiles()?.map { it.absolutePath }?.toTypedArray()
                    if (!files.isNullOrEmpty()) {
                        MediaScannerConnection.scanFile(context, files, null, null)
                    }
                }

                refreshVideos()
                isError = false
                progressValue = null
                statusText = "Kaydedildi · Videolar bölümünden izleyebilirsiniz"
            } catch (e: Exception) {
                isError = true
                progressValue = null
                statusText = "İndirilemedi: ${e.localizedMessage ?: "Bilinmeyen hata"}"
            } finally {
                isDownloading = false
            }
        }
    }

    fun openVideo(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            isError = true
            statusText = "Video oynatıcı açılamadı."
        }
    }

    fun shareVideo(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Videoyu Paylaş"))
        } catch (_: Exception) {
        }
    }

    fun deleteVideo(file: File) {
        if (file.exists()) {
            file.delete()
            refreshVideos()
        }
    }

    BackHandler(enabled = showLibrary) {
        showLibrary = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFFFFF), Color(0xFFF6F7FA))
                )
            )
            .statusBarsPadding()
    ) {
        AnimatedContent(
            targetState = showLibrary,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "screen_switch"
        ) { inLibrary ->
            if (!inLibrary) {
                HomeScreen(
                    isDownloading = isDownloading,
                    progressValue = progressValue,
                    statusText = statusText,
                    isError = isError,
                    videoCount = videos.size,
                    onOrbClick = { startDownloadFromClipboard() },
                    onOpenLibrary = {
                        refreshVideos()
                        showLibrary = true
                    }
                )
            } else {
                LibraryScreen(
                    videos = videos,
                    onBack = { showLibrary = false },
                    onPlay = { openVideo(it) },
                    onShare = { shareVideo(it) },
                    onDelete = { deleteVideo(it) }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    isDownloading: Boolean,
    progressValue: Int?,
    statusText: String,
    isError: Boolean,
    videoCount: Int,
    onOrbClick: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "DROP",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 5.sp,
            color = Color(0xFF8C929C)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier
                    .size(184.dp)
                    .scale(if (isDownloading) pulseScale else 1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x14000000),
                        spotColor = Color(0x1A000000)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isDownloading,
                        onClick = onOrbClick
                    ),
                shape = CircleShape,
                color = Color(0xFFFCFCFD),
                border = BorderStroke(1.5.dp, Color(0xFFE7E9EF))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isDownloading && progressValue != null) {
                        Text(
                            text = "%$progressValue",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111111)
                        )
                    } else if (isDownloading) {
                        Text(
                            text = "•••",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Light,
                            color = Color(0xFF111111)
                        )
                    } else {
                        DownloadArrowIcon(modifier = Modifier.size(34.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = statusText,
                fontSize = 13.sp,
                color = if (isError) Color(0xFFD93025) else Color(0xFF7D838E),
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
        }

        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onOpenLibrary),
            shape = RoundedCornerShape(50),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE9ECF2)),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GridDotsIcon(modifier = Modifier.size(16.dp))
                Text(
                    text = if (videoCount > 0) "VİDEOLAR · $videoCount" else "VİDEOLAR",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    color = Color(0xFF181A1F)
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    videos: List<File>,
    onBack: () -> Unit,
    onPlay: (File) -> Unit,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE9ECF2))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "←",
                        fontSize = 18.sp,
                        color = Color(0xFF111111)
                    )
                }
            }

            Text(
                text = "VİDEOLAR",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
                color = Color(0xFF181A1F)
            )

            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${videos.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8C929C)
                )
            }
        }

        if (videos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Henüz video yok",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2B2E36)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "İndirdiğiniz videolar burada görünecek",
                        fontSize = 13.sp,
                        color = Color(0xFF8C929C)
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(videos, key = { it.absolutePath }) { file ->
                    VideoItemCard(
                        file = file,
                        onPlay = { onPlay(file) },
                        onShare = { onShare(file) },
                        onDelete = { onDelete(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoItemCard(
    file: File,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        thumbnail = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFECEFF4)),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF1F3F6)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "▶",
                        fontSize = 10.sp,
                        color = Color(0xFF111111)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.nameWithoutExtension,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF14161A),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(file.length())} · ${formatDate(file.lastModified())}",
                    fontSize = 12.sp,
                    color = Color(0xFF8C929C)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallActionChip(label = "↗", onClick = onShare)
                SmallActionChip(label = "×", onClick = onDelete, isDanger = true)
            }
        }
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    onClick: () -> Unit,
    isDanger: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFFF6F7FA))
            .border(1.dp, Color(0xFFECEFF4), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = if (isDanger) Color(0xFFD93025) else Color(0xFF181A1F)
        )
    }
}

@Composable
private fun DownloadArrowIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = Color(0xFF111111)

        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.16f),
            end = Offset(w * 0.5f, h * 0.68f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        val chevron = Path().apply {
            moveTo(w * 0.28f, h * 0.48f)
            lineTo(w * 0.5f, h * 0.70f)
            lineTo(w * 0.72f, h * 0.48f)
        }
        drawPath(path = chevron, color = color, style = stroke)
        drawLine(
            color = color,
            start = Offset(w * 0.22f, h * 0.84f),
            end = Offset(w * 0.78f, h * 0.84f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun GridDotsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Color(0xFF181A1F)
        val r = size.width * 0.1f
        val positions = listOf(0.2f, 0.5f, 0.8f)
        for (px in positions) {
            for (py in positions) {
                drawCircle(
                    color = color,
                    radius = r,
                    center = Offset(size.width * px, size.height * py)
                )
            }
        }
    }
}
