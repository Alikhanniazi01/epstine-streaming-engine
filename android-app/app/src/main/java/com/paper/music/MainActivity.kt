package com.paper.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val APP_URL = "https://epstine-streaming-engine-1.onrender.com"
    }

    private lateinit var webView: WebView
    private var controller: MediaController? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBackPress = 0L

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(uris?.toTypedArray() ?: emptyArray())
        fileUploadCallback = null
    }

    private val progressPoller = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null) {
                val playing = c.isPlaying
                val posSec = (c.currentPosition.coerceAtLeast(0L)) / 1000.0
                val durSec = (c.duration.coerceAtLeast(0L)) / 1000.0
                webView.evaluateJavascript(
                    "window.__onNativeState && window.__onNativeState($playing, $posSec, $durSec);",
                    null
                )
            }
            mainHandler.postDelayed(this, 400)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Android 13+ notification permission for Spotify-style media controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Connect to native Foreground MediaSessionService (ExoPlayer)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val posSec = (c.currentPosition.coerceAtLeast(0L)) / 1000.0
                    val durSec = (c.duration.coerceAtLeast(0L)) / 1000.0
                    webView.evaluateJavascript(
                        "window.__onNativeState && window.__onNativeState($isPlaying, $posSec, $durSec);",
                        null
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        webView.evaluateJavascript(
                            "window.__onNativeEnded && window.__onNativeEnded();",
                            null
                        )
                    }
                }
            })
            mainHandler.post(progressPoller)
        }, MoreExecutors.directExecutor())

        // Full-screen WebView hosting the exact Paper UI with native audio bridge
        webView = WebView(this).apply {
            setBackgroundColor(0xFFECE5D8.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                // Clean Chrome UA so Google Sign-In works properly
                userAgentString = userAgentString.replace("; wv", "")
            }

            addJavascriptInterface(PaperNativeBridge(), "PaperNative")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    filePickerLauncher.launch(arrayOf("audio/*"))
                    return true
                }
            }

            loadUrl(APP_URL)
        }

        setContentView(webView)

        // Native Back Button: step back inside Paper UI first; double-back on Home moves app to background (keeps music playing!)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : 'EXIT'") { result ->
                    if (result != null && result.contains("EXIT")) {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPress < 2500L) {
                            moveTaskToBack(true)
                        } else {
                            lastBackPress = now
                            Toast.makeText(this@MainActivity, "Press back again to leave (music keeps playing)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    inner class PaperNativeBridge {
        @JavascriptInterface
        fun playTrack(id: String, title: String, artist: String, videoId: String, cover: String) {
            lifecycleScope.launch {
                val track = Track(id = id, title = title, artist = artist, videoId = videoId, cover = cover)
                val resolved = AudioEngine.resolveAudioStreamUrl(track)
                if (resolved == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Could not load audio stream", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val (streamUrl, updated) = resolved
                runOnUiThread {
                    val c = controller ?: return@runOnUiThread
                    val meta = MediaMetadata.Builder()
                        .setTitle(updated.title)
                        .setArtist(updated.artist)
                        .setArtworkUri(if (updated.cover.isNotBlank()) Uri.parse(updated.cover) else null)
                        .build()
                    val item = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaId(updated.id)
                        .setMediaMetadata(meta)
                        .build()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                }
            }
        }

        @JavascriptInterface
        fun togglePlay() {
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                if (c.isPlaying) c.pause() else c.play()
            }
        }

        @JavascriptInterface
        fun seekTo(seconds: Double) {
            runOnUiThread {
                controller?.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
            }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread {
                controller?.pause()
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressPoller)
        controller?.release()
        super.onDestroy()
    }
}
