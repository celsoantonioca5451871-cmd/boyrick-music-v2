package com.boywick.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Bg = Color(0xFF25282D)
private val Panel = Color(0xFF30343A)
private val Line = Color(0xFF474D56)
private val Sky = Color(0xFF4DB8FF)
private val Sky2 = Color(0xFF9AD8FF)
private val Mut = Color(0xFFA3AFBA)
private val Ink = Color(0xFF00131F)

data class Track(val uri: Uri, val title: String, val artist: String)

fun makeTrack(ctx: Context, u: Uri): Track {
    var name = u.lastPathSegment ?: "Faixa"
    try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
    } catch (_: Exception) {
    }
    name = name.substringBeforeLast('.')
    val p = name.split(" - ", limit = 2)
    return if (p.size == 2) Track(u, p[1], p[0]) else Track(u, name, "Artista desconhecido")
}

fun loadTracks(ctx: Context): List<Track> {
    val raw = ctx.getSharedPreferences("brmusic", 0).getString("uris", "[]") ?: "[]"
    val out = mutableListOf<Track>()
    try {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) out.add(makeTrack(ctx, Uri.parse(a.getString(i))))
    } catch (_: Exception) {
    }
    return out
}

fun saveTracks(ctx: Context, list: List<Track>) {
    val a = JSONArray()
    list.forEach { a.put(it.uri.toString()) }
    ctx.getSharedPreferences("brmusic", 0).edit().putString("uris", a.toString()).apply()
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        player = ExoPlayer.Builder(this).build()
        setContent { MusicApp(player) }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
fun CtlBtn(label: String, on: Boolean = false, big: Boolean = false, onClick: () -> Unit) {
    val s: Dp = if (big) 72.dp else 50.dp
    val filled = big || on
    Box(
        Modifier
            .size(s)
            .clip(CircleShape)
            .background(if (filled) Sky else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (filled) Ink else Sky2,
            fontSize = if (big) 30.sp else 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MusicApp(player: ExoPlayer) {
    val ctx = LocalContext.current
    val tracks = remember { mutableStateListOf<Track>().apply { addAll(loadTracks(ctx)) } }
    var idx by remember { mutableStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    var drag by remember { mutableStateOf(0f) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    fun playAt(i: Int) {
        if (i !in tracks.indices) return
        idx = i
        player.setMediaItem(MediaItem.fromUri(tracks[i].uri))
        player.prepare()
        player.play()
    }

    fun next(auto: Boolean) {
        if (tracks.isEmpty()) return
        if (shuffle && tracks.size > 1) {
            var n: Int
            do {
                n = tracks.indices.random()
            } while (n == idx)
            playAt(n)
            return
        }
        if (idx + 1 < tracks.size) playAt(idx + 1)
        else if (repeat == 1 || !auto) playAt(0)
        else player.pause()
    }

    fun prev() {
        if (tracks.isEmpty()) return
        if (player.currentPosition > 3000) player.seekTo(0)
        else playAt(if (idx > 0) idx - 1 else tracks.size - 1)
    }

    fun removeAt(i: Int) {
        tracks.removeAt(i)
        saveTracks(ctx, tracks)
        if (i == idx) {
            player.stop()
            idx = -1
            if (tracks.isNotEmpty()) playAt(min(i, tracks.size - 1))
        } else if (i < idx) idx--
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (tracks.none { it.uri == u }) tracks.add(makeTrack(ctx, u))
        }
        saveTracks(ctx, tracks)
        if (idx < 0 && tracks.isNotEmpty()) playAt(0)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (repeat == 2) {
                        player.seekTo(0)
                        player.play()
                    } else next(true)
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }

    val t by rememberInfiniteTransition(label = "viz").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t"
    )
    val tState = rememberTransformableState { zoomChange, _, _ ->
        zoom = (zoom * zoomChange).coerceIn(0.6f, 2.5f)
    }
    val cur = tracks.getOrNull(idx)

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).border(2.dp, Sky, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("B", color = Sky, fontSize = 24.sp, lineHeight = 24.sp, fontWeight = FontWeight.Black)
                    Text("music", color = Sky2, fontSize = 8.sp, lineHeight = 8.sp)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Boy Rick", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("Music Slide Treme", color = Mut, fontSize = 12.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Sky)
                    .clickable { picker.launch(arrayOf("audio/*")) }
                    .padding(16.dp, 10.dp)
            ) { Text("Adicionar faixas", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
        }

        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(28.dp))
                .background(Panel)
                .border(1.dp, Line, RoundedCornerShape(28.dp))
                .transformable(tState, canPan = { false })
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (drag < -120f) next(false) else if (drag > 120f) prev()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    ) { _, d -> drag += d }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize().scale(zoom)) {
                val w = size.height
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = w * 0.24f
                val n = 64
                for (i in 0 until n) {
                    val v = if (playing)
                        0.2f + 0.6f * abs(sin(t * 12.566f + i * 0.45f)) * abs(cos(i * 0.17f + t * 6.283f))
                    else 0.08f
                    val a = i.toFloat() / n * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val len = w * 0.03f + v * w * 0.2f
                    drawLine(
                        Sky,
                        Offset(c.x + cos(a) * r, c.y + sin(a) * r),
                        Offset(c.x + cos(a) * (r + len), c.y + sin(a) * (r + len)),
                        strokeWidth = w * 0.012f,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(Sky, radius = r * 0.92f, center = c, style = Stroke(width = w * 0.006f))
            }
            Column(Modifier.scale(zoom), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("B", color = Sky, fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.Black)
                Text("music", color = Sky2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Desliza para mudar de faixa · pinça para ampliar",
                color = Mut, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            )
        }

        Column(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cur?.title ?: "Nenhuma faixa", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(cur?.artist ?: "Adiciona música para começar", color = Mut, fontSize = 14.sp)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(if (scrub != null) (scrub!! * dur).toLong() else pos), color = Mut, fontSize = 12.sp)
            Slider(
                value = scrub ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { player.seekTo((it * dur).toLong()) }
                    scrub = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Sky, activeTrackColor = Sky, inactiveTrackColor = Color(0xFF4A515B)
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            Text(fmt(dur), color = Mut, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtlBtn("⇄", on = shuffle) { shuffle = !shuffle }
            CtlBtn("⏮") { prev() }
            CtlBtn(if (playing) "⏸" else "▶", big = true) {
                when {
                    idx < 0 -> playAt(0)
                    player.isPlaying -> player.pause()
                    player.playbackState == Player.STATE_ENDED -> playAt(idx)
                    else -> player.play()
                }
            }
            CtlBtn("⏭") { next(false) }
            CtlBtn(if (repeat == 2) "↻1" else "↻", on = repeat > 0) { repeat = (repeat + 1) % 3 }
        }

        Text(
            "Playlist (${tracks.size})", color = Color.White, fontSize = 16.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 6.dp)
        )
        if (tracks.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(16.dp).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ainda sem faixas.\nToca em «Adicionar faixas» para escolher ficheiros de áudio.",
                    color = Mut, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            itemsIndexed(tracks) { i, tr ->
                val on = i == idx
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) Panel else Color.Transparent)
                        .clickable { playAt(i) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Sky else Color(0xFF3B4149)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((i + 1).toString(), color = if (on) Ink else Sky, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(tr.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr.artist, color = Mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(36.dp).clickable { removeAt(i) }, contentAlignment = Alignment.Center) {
                        Text("×", color = Mut, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}
