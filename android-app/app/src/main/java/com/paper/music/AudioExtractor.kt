package com.paper.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val videoId: String = "",
    val cover: String = "",
    val durationSec: Int = 0,
    val hue: Float = 18f
)

data class UserPlaylist(
    val id: String,
    val name: String,
    val hue: Float,
    val tracks: List<Track>
)

object AudioEngine {
    private const val RENDER_BASE = "https://epstine-streaming-engine-1.onrender.com"
    private var npInitialized = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val niceHues = floatArrayOf(18f, 32f, 200f, 262f, 150f, 340f, 285f, 8f, 42f, 175f)

    fun hueFor(text: String): Float {
        var h = 0
        for (c in text) h = (h * 31 + c.code) and 0x7fffffff
        return niceHues[h % niceHues.size]
    }

    private fun ensureNewPipe() {
        if (npInitialized) return
        synchronized(this) {
            if (npInitialized) return
            NewPipe.init(object : Downloader() {
                override fun execute(request: NpRequest): NpResponse {
                    val b = Request.Builder()
                        .url(request.url())
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    request.headers().forEach { (k, vals) ->
                        vals.forEach { v -> b.addHeader(k, v) }
                    }
                    val bodyBytes = request.dataToSend()
                    if (bodyBytes != null && request.httpMethod().equals("POST", ignoreCase = true)) {
                        b.post(bodyBytes.toRequestBody("application/json".toMediaType()))
                    } else if (request.httpMethod().equals("HEAD", ignoreCase = true)) {
                        b.head()
                    }
                    http.newCall(b.build()).execute().use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        return NpResponse(
                            resp.code,
                            resp.message,
                            resp.headers.toMultimap(),
                            respBody,
                            resp.request.url.toString()
                        )
                    }
                }
            })
            npInitialized = true
        }
    }

    /** Direct on-device audio-only stream extraction (zero video decoding -> cool phone!) */
    suspend fun resolveAudioStreamUrl(track: Track): Pair<String, Track>? = withContext(Dispatchers.IO) {
        ensureNewPipe()
        var vid = track.videoId
        var updated = track

        if (vid.isBlank()) {
            val found = searchYouTubeDirect("${track.title} ${track.artist}").firstOrNull()
            if (found != null) {
                vid = found.videoId
                updated = track.copy(
                    videoId = vid,
                    cover = track.cover.ifBlank { found.cover },
                    durationSec = if (track.durationSec > 0) track.durationSec else found.durationSec
                )
            }
        }
        if (vid.isBlank()) return@withContext null

        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid")
            val audioStreams = info.audioStreams.orEmpty()
            val best = audioStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
            val url = best?.content ?: return@withContext null
            Pair(url, updated.copy(durationSec = if (info.duration > 0) info.duration.toInt() else updated.durationSec))
        } catch (_e: Exception) {
            null
        }
    }

    /** Ultra-fast on-device YouTube search directly from the user's phone (no Render hop!) */
    suspend fun searchYouTubeDirect(query: String): List<Track> = withContext(Dispatchers.IO) {
        ensureNewPipe()
        try {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .take(15)
                .mapNotNull { item ->
                    val url = item.url ?: return@mapNotNull null
                    val vid = Regex("v=([a-zA-Z0-9_-]{11})").find(url)?.groupValues?.getOrNull(1)
                        ?: return@mapNotNull null
                    val thumb = item.thumbnails.lastOrNull()?.url
                        ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                    Track(
                        id = "yt_$vid",
                        title = item.name ?: "Unknown",
                        artist = (item.uploaderName ?: "YouTube").replace(" - Topic", ""),
                        videoId = vid,
                        cover = thumb,
                        durationSec = item.duration.toInt().coerceAtLeast(0),
                        hue = hueFor(item.name ?: vid)
                    )
                }
        } catch (_e: Exception) {
            searchViaItunes(query)
        }
    }

    /** ~200ms typo-tolerant Apple iTunes fallback */
    suspend fun searchViaItunes(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$enc&media=music&entity=song&limit=14")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("results") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val title = o.optString("trackName", "Unknown")
                    val art = o.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                    Track(
                        id = "it_${o.optLong("trackId")}",
                        title = title,
                        artist = o.optString("artistName", "Artist"),
                        videoId = "",
                        cover = art,
                        durationSec = (o.optInt("trackTimeMillis", 0) / 1000),
                        hue = hueFor(title)
                    )
                }
            }
        } catch (_e: Exception) {
            emptyList()
        }
    }

    /** Instant 100-track Spotify playlist import via your Render server */
    suspend fun importSpotifyPlaylist(url: String): UserPlaylist = withContext(Dispatchers.IO) {
        val body = JSONObject().put("url", url).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$RENDER_BASE/api/import-spotify").post(body).build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            val json = JSONObject(raw)
            if (!resp.isSuccessful) throw IllegalStateException(json.optString("error", "Import failed"))
            val name = json.optString("name", "Imported Playlist")
            val arr = json.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val title = o.optString("title", "Track")
                Track(
                    id = "sp_${System.currentTimeMillis()}_$i",
                    title = title,
                    artist = o.optString("artist", "Unknown"),
                    videoId = o.optString("videoId", ""),
                    cover = o.optString("thumbnail", ""),
                    durationSec = o.optInt("duration", 0),
                    hue = hueFor(title)
                )
            }
            UserPlaylist(id = "pl_${System.currentTimeMillis()}", name = name, hue = hueFor(name), tracks = tracks)
        }
    }

    // Permanent local storage on Android device (never wiped on server restarts!)
    fun saveLibrary(ctx: Context, liked: List<Track>, playlists: List<UserPlaylist>) {
        val prefs = ctx.getSharedPreferences("paper_music_db", Context.MODE_PRIVATE)
        val likedArr = JSONArray().apply { liked.forEach { put(it.toJson()) } }
        val plArr = JSONArray().apply {
            playlists.forEach { pl ->
                put(JSONObject().apply {
                    put("id", pl.id); put("name", pl.name); put("hue", pl.hue.toDouble())
                    put("tracks", JSONArray().apply { pl.tracks.forEach { put(it.toJson()) } })
                })
            }
        }
        prefs.edit()
            .putString("liked", likedArr.toString())
            .putString("playlists", plArr.toString())
            .apply()
    }

    fun loadLibrary(ctx: Context): Pair<List<Track>, List<UserPlaylist>> {
        val prefs = ctx.getSharedPreferences("paper_music_db", Context.MODE_PRIVATE)
        val liked = parseTracks(prefs.getString("liked", "[]") ?: "[]")
        val pls = mutableListOf<UserPlaylist>()
        try {
            val arr = JSONArray(prefs.getString("playlists", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pls.add(
                    UserPlaylist(
                        id = o.optString("id", "pl_$i"),
                        name = o.optString("name", "Playlist"),
                        hue = o.optDouble("hue", 18.0).toFloat(),
                        tracks = parseTracks(o.optJSONArray("tracks")?.toString() ?: "[]")
                    )
                )
            }
        } catch (_e: Exception) {}
        return Pair(liked, pls)
    }

    private fun Track.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("artist", artist)
        put("videoId", videoId); put("cover", cover); put("durationSec", durationSec); put("hue", hue.toDouble())
    }

    private fun parseTracks(jsonStr: String): List<Track> {
        val out = mutableListOf<Track>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Track(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        videoId = o.optString("videoId"),
                        cover = o.optString("cover"),
                        durationSec = o.optInt("durationSec"),
                        hue = o.optDouble("hue", 18.0).toFloat()
                    )
                )
            }
        } catch (_e: Exception) {}
        return out
    }

    val starterTracks = listOf(
        Track("yt_H64a2ggVIWc", "Blinding Lights", "The Weeknd", "H64a2ggVIWc", "https://i.ytimg.com/vi/H64a2ggVIWc/hqdefault.jpg", 200, 18f),
        Track("yt_YnARca2TL4Q", "Pasoori", "Ali Sethi, Shae Gill", "YnARca2TL4Q", "https://i.ytimg.com/vi/YnARca2TL4Q/hqdefault.jpg", 224, 32f),
        Track("yt_TUVcZfQe-Kw", "Levitating", "Dua Lipa", "TUVcZfQe-Kw", "https://i.ytimg.com/vi/TUVcZfQe-Kw/hqdefault.jpg", 203, 262f),
        Track("yt_plnfIj7dkJE", "Starboy", "The Weeknd, Daft Punk", "plnfIj7dkJE", "https://i.ytimg.com/vi/plnfIj7dkJE/hqdefault.jpg", 230, 340f),
        Track("yt_V9PVRfjEBTI", "BIRDS OF A FEATHER", "Billie Eilish", "V9PVRfjEBTI", "https://i.ytimg.com/vi/V9PVRfjEBTI/hqdefault.jpg", 210, 200f),
        Track("yt_x18b0D8sTwo", "Excuses", "AP Dhillon, Gurinder Gill", "x18b0D8sTwo", "https://i.ytimg.com/vi/x18b0D8sTwo/hqdefault.jpg", 176, 150f)
    )
}
