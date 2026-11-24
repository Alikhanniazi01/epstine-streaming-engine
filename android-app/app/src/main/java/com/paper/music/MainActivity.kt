package com.paper.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private val PaperBg = Color(0xFFECE5D8)
private val PaperSurface = Color(0xFFF8F3EA)
private val PaperInk = Color(0xFF1C1814)
private val PaperMuted = Color(0xFF7A7064)
private val PaperLine = Color(0xFFDAD1C0)

private fun hueColor(h: Float): Color = Color.hsl(h % 360f, 0.85f, 0.64f)

private fun fmtSec(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or denied gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Android 13+ notification permission so the Spotify-style media notification appears
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val controllerState = mutableStateOf<MediaController?>(null)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        future.addListener({
            controller = future.get()
            controllerState.value = controller
        }, MoreExecutors.directExecutor())

        setContent {
            PaperMusicApp(controller = controllerState.value, onExit = { finish() })
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@Composable
fun PaperMusicApp(controller: MediaController?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf("home") } // home | search | lib | pl
    var activePlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    val loaded = remember { AudioEngine.loadLibrary(ctx) }
    var likedTracks by remember { mutableStateOf(loaded.first) }
    var playlists by remember { mutableStateOf(loaded.second) }

    var currentTrack by remember { mutableStateOf(AudioEngine.starterTracks.first()) }
    var queue by remember { mutableStateOf(AudioEngine.starterTracks) }
    var queueIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoadingStream by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(200_000L) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var lastBackPress by remember { mutableStateOf(0L) }

    fun playTrack(track: Track, list: List<Track> = queue) {
        val idx = list.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            queue = list
            queueIndex = idx
        } else {
            queue = listOf(track) + list
            queueIndex = 0
        }
        currentTrack = track
        isLoadingStream = true

        scope.launch {
            val resolved = AudioEngine.resolveAudioStreamUrl(track)
            isLoadingStream = false
            if (resolved == null) {
                Toast.makeText(ctx, "Could not load audio stream — try another track", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (streamUrl, updatedTrack) = resolved
            currentTrack = updatedTrack
            val c = controller ?: return@launch
            val meta = MediaMetadata.Builder()
                .setTitle(updatedTrack.title)
                .setArtist(updatedTrack.artist)
                .setArtworkUri(if (updatedTrack.cover.isNotBlank()) Uri.parse(updatedTrack.cover) else null)
                .build()
            val item = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaId(updatedTrack.id)
                .setMediaMetadata(meta)
                .build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    fun stepTrack(delta: Int) {
        if (queue.isEmpty()) return
        val next = (queueIndex + delta + queue.size) % queue.size
        playTrack(queue[next], queue)
    }

    // Sync live ExoPlayer playback state & auto-advance
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) stepTrack(1)
            }
        }
        controller.addListener(listener)
        while (true) {
            isPlaying = controller.isPlaying
            positionMs = controller.currentPosition.coerceAtLeast(0L)
            val d = controller.duration
            if (d > 0) durationMs = d
            delay(400)
        }
    }

    // Native Android Back Button + Double-Back-to-Exit (keeps music playing in background!)
    BackHandler {
        when {
            showImportDialog -> showImportDialog = false
            showNowPlaying -> showNowPlaying = false
            tab == "pl" -> tab = "lib"
            tab != "home" -> tab = "home"
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2500L) {
                    onExit()
                } else {
                    lastBackPress = now
                    Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val accent = hueColor(currentTrack.hue)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // MAIN VIEWS
        when (tab) {
            "home" -> HomeView(
                current = currentTrack,
                likedCount = likedTracks.size,
                onPlay = { t, l -> playTrack(t, l) },
                onImport = { showImportDialog = true },
                onOpenLiked = {
                    activePlaylist = UserPlaylist("__liked__", "Liked Songs", 18f, likedTracks)
                    tab = "pl"
                }
            )
            "search" -> SearchView(
                query = searchQuery,
                results = searchResults,
                isSearching = isSearching,
                onQueryChange = { q ->
                    searchQuery = q
                    scope.launch {
                        if (q.trim().length < 2) {
                            searchResults = emptyList()
                            return@launch
                        }
                        isSearching = true
                        delay(200)
                        if (searchQuery == q) {
                            val fast = AudioEngine.searchViaItunes(q.trim())
                            if (fast.isNotEmpty()) searchResults = fast
                            else searchResults = AudioEngine.searchYouTubeDirect(q.trim())
                        }
                        isSearching = false
                    }
                },
                onPlay = { t -> playTrack(t, if (searchResults.isNotEmpty()) searchResults else listOf(t)) }
            )
            "lib" -> LibraryView(
                likedCount = likedTracks.size,
                playlists = playlists,
                onImport = { showImportDialog = true },
                onOpenLiked = {
                    activePlaylist = UserPlaylist("__liked__", "Liked Songs", 18f, likedTracks)
                    tab = "pl"
                },
                onOpenPlaylist = { pl ->
                    activePlaylist = pl
                    tab = "pl"
                }
            )
            "pl" -> activePlaylist?.let { pl ->
                PlaylistDetailView(
                    playlist = pl,
                    onBack = { tab = "lib" },
                    onPlay = { t -> playTrack(t, pl.tracks) }
                )
            }
        }

        // FLOATING INK MINI-PLAYER
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 74.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(PaperInk)
                .clickable { showNowPlaying = true }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SquircleArt(currentTrack, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isLoadingStream) "Loading ${currentTrack.title}…" else currentTrack.title,
                    color = PaperBg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentTrack.artist,
                    color = Color(0xFFB9AE9E), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { stepTrack(1) }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = PaperBg)
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clickable {
                        val c = controller
                        if (c == null || c.mediaItemCount == 0) playTrack(currentTrack, queue)
                        else if (c.isPlaying) c.pause() else c.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Color(0x33FFFFFF), -90f, 360f, false, style = Stroke(3.dp.toPx()))
                    drawArc(accent, -90f, progress * 360f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = PaperBg
                )
            }
        }

        // FLOATING INK PILL BOTTOM NAV
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(PaperInk)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("home" to "Home", "search" to "Search", "lib" to "Library").forEach { (id, label) ->
                val active = tab == id || (tab == "pl" && id == "lib")
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (active) PaperBg else Color.Transparent)
                        .clickable { tab = id }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        label,
                        color = if (active) PaperInk else Color(0xFFB9AE9E),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // FULL-SCREEN DYNAMIC-HUE NOW PLAYING SHEET
        if (showNowPlaying) {
            val isLiked = likedTracks.any { it.id == currentTrack.id }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(accent)
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showNowPlaying = false }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = PaperInk, modifier = Modifier.size(28.dp))
                    }
                    Text("NOW PLAYING", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = PaperInk)
                    Spacer(Modifier.size(48.dp))
                }

                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SquircleArt(currentTrack, 290.dp)
                }
                Spacer(Modifier.weight(1f))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(currentTrack.title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentTrack.artist, fontSize = 15.sp, color = PaperInk.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (isLiked) PaperInk else Color.Transparent)
                            .border(1.dp, PaperInk.copy(alpha = 0.35f), RoundedCornerShape(99.dp))
                            .clickable {
                                likedTracks = if (isLiked) likedTracks.filterNot { it.id == currentTrack.id }
                                else listOf(currentTrack) + likedTracks
                                AudioEngine.saveLibrary(ctx, likedTracks, playlists)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(if (isLiked) "Liked" else "Like", color = if (isLiked) PaperBg else PaperInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Slider(
                    value = progress,
                    onValueChange = { p -> controller?.seekTo((p * durationMs).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = PaperInk,
                        activeTrackColor = PaperInk,
                        inactiveTrackColor = PaperInk.copy(alpha = 0.22f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtSec((positionMs / 1000).toInt()), fontSize = 12.sp, color = PaperInk)
                    Text(fmtSec((durationMs / 1000).toInt()), fontSize = 12.sp, color = PaperInk)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { stepTrack(-1) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = PaperInk, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(PaperInk)
                            .clickable {
                                val c = controller
                                if (c == null || c.mediaItemCount == 0) playTrack(currentTrack, queue)
                                else if (c.isPlaying) c.pause() else c.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = accent,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.width(20.dp))
                    IconButton(onClick = { stepTrack(1) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = PaperInk, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // SPOTIFY IMPORT DIALOG
        if (showImportDialog) {
            var urlInput by remember { mutableStateOf("") }
            var importing by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!importing) showImportDialog = false },
                containerColor = PaperSurface,
                title = { Text("Import Spotify Playlist", fontWeight = FontWeight.ExtraBold, color = PaperInk) },
                text = {
                    Column {
                        Text("Paste any public Spotify playlist link:", fontSize = 13.sp, color = PaperMuted)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text("https://open.spotify.com/playlist/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !importing && urlInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PaperInk, contentColor = PaperBg),
                        onClick = {
                            importing = true
                            scope.launch {
                                try {
                                    val pl = AudioEngine.importSpotifyPlaylist(urlInput.trim())
                                    playlists = listOf(pl) + playlists
                                    AudioEngine.saveLibrary(ctx, likedTracks, playlists)
                                    activePlaylist = pl
                                    tab = "pl"
                                    showImportDialog = false
                                    Toast.makeText(ctx, "Imported ${pl.name} (${pl.tracks.size} songs)", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                                } finally {
                                    importing = false
                                }
                            }
                        }
                    ) { Text(if (importing) "Importing…" else "Import") }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) { Text("Cancel", color = PaperMuted) }
                }
            )
        }
    }
}

@Composable
fun SquircleArt(track: Track, size: Dp) {
    val bgBrush = Brush.linearGradient(
        listOf(hueColor(track.hue), Color.hsl((track.hue + 330f) % 360f, 0.6f, 0.25f))
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 28))
            .background(bgBrush)
    ) {
        if (track.cover.isNotBlank()) {
            AsyncImage(
                model = track.cover,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun HomeView(
    current: Track,
    likedCount: Int,
    onPlay: (Track, List<Track>) -> Unit,
    onImport: () -> Unit,
    onOpenLiked: () -> Unit
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 12 -> "Morning"
        hour < 17 -> "Afternoon"
        hour < 21 -> "Evening"
        else -> "Late Night"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 180.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text("Paper Music", color = PaperMuted, fontSize = 14.sp)
                    Text(greeting, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(PaperSurface)
                        .border(1.dp, PaperLine, RoundedCornerShape(99.dp))
                        .clickable { onImport() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("+ Import", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PaperInk)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(PaperSurface)
                    .border(1.dp, PaperLine, RoundedCornerShape(99.dp))
                    .clickable { onOpenLiked() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("♥ Liked · $likedCount", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PaperInk)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(195.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(hueColor(current.hue))
                    .clickable { onPlay(current, AudioEngine.starterTracks) }
                    .padding(20.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Continue", fontSize = 13.sp, color = PaperInk.copy(alpha = 0.65f))
                    Text(current.title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, fontSize = 14.sp, color = PaperInk.copy(alpha = 0.7f), maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                SquircleArt(current, 100.dp)
            }
            Spacer(Modifier.height(24.dp))
            Text("On rotation", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk)
            Spacer(Modifier.height(10.dp))
        }
        items(AudioEngine.starterTracks) { t ->
            TrackRowItem(t) { onPlay(t, AudioEngine.starterTracks) }
        }
    }
}

@Composable
fun SearchView(
    query: String,
    results: List<Track>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onPlay: (Track) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp)
    ) {
        Text("Search", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Songs, artists…", color = PaperMuted) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PaperSurface,
                unfocusedContainerColor = PaperSurface,
                focusedBorderColor = PaperInk,
                unfocusedBorderColor = PaperLine
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        if (isSearching) Text("Searching…", color = PaperMuted, fontSize = 13.sp)
        LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
            items(if (results.isNotEmpty()) results else AudioEngine.starterTracks) { t ->
                TrackRowItem(t) { onPlay(t) }
            }
        }
    }
}

@Composable
fun LibraryView(
    likedCount: Int,
    playlists: List<UserPlaylist>,
    onImport: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenPlaylist: (UserPlaylist) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 180.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Library", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(PaperSurface)
                        .border(1.dp, PaperLine, RoundedCornerShape(99.dp))
                        .clickable { onImport() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("+ Spotify", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PaperInk)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLiked() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(percent = 28)).background(hueColor(18f)),
                    contentAlignment = Alignment.Center
                ) { Text("♥", fontSize = 22.sp, color = PaperInk) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PaperInk)
                    Text("$likedCount songs", fontSize = 13.sp, color = PaperMuted)
                }
            }
        }
        items(playlists) { pl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPlaylist(pl) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SquircleArt(pl.tracks.firstOrNull() ?: Track(pl.id, pl.name, "", hue = pl.hue), 54.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pl.tracks.size} songs", fontSize = 13.sp, color = PaperMuted)
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailView(playlist: UserPlaylist, onBack: () -> Unit, onPlay: (Track) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 180.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(PaperSurface)
                    .border(1.dp, PaperLine, RoundedCornerShape(99.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("← Back", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PaperInk)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(hueColor(playlist.hue))
                    .padding(20.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${playlist.tracks.size} songs", fontSize = 13.sp, color = PaperInk.copy(alpha = 0.65f))
                    Text(playlist.name, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = PaperInk)
                }
                if (playlist.tracks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(PaperInk)
                            .clickable { onPlay(playlist.tracks.first()) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) { Text("Play", color = PaperBg, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        items(playlist.tracks) { t ->
            TrackRowItem(t) { onPlay(t) }
        }
    }
}

@Composable
fun TrackRowItem(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquircleArt(track, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, fontSize = 13.sp, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.durationSec > 0) {
            Text(fmtSec(track.durationSec), fontSize = 12.sp, color = PaperMuted)
        }
    }
}
