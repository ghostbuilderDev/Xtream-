package fr.yoann.streamboxvision

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var routeButton: MediaRouteButton
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var castContext: CastContext
    private var url = ""
    private var title = ""
    private var mime = "video/mp4"
    private var isLive = false
    private var startPositionMs = 0L
    private var pendingCast = false
    private var retryAttempt = 0
    private var lastDiagnostic: SavedDiagnostic? = null

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) { castTo(session) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { if (pendingCast) castTo(session) }
        override fun onSessionEnded(session: CastSession, error: Int) { if (!exoPlayer.isPlaying) exoPlayer.play() }
        override fun onSessionStartFailed(session: CastSession, error: Int) { pendingCast = false; createCastDiagnostic("session_start_failed", error) }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { createCastDiagnostic("session_resume_failed", error) }
        override fun onSessionStarting(session: CastSession) { pendingCast = true }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        routeButton = findViewById(R.id.playerCastButton)
        url = intent.getStringExtra("url").orEmpty()
        title = intent.getStringExtra("title").orEmpty()
        mime = intent.getStringExtra("mime") ?: "video/mp4"
        isLive = intent.getBooleanExtra("isLive", false)
        startPositionMs = intent.getLongExtra("startPositionMs", 0L)

        castContext = CastContext.getSharedInstance(this)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, routeButton)
        castContext.sessionManager.addSessionManagerListener(castListener, CastSession::class.java)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.21 LibVLC/3.0.21 StreamBoxVision/10.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25_000)
            .setReadTimeoutMs(45_000)

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        exoPlayer = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        playerView.player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = url
                if (retryAttempt == 0 && isLive && current.contains(".m3u8", true)) {
                    retryAttempt++
                    val alternate = current.replace(Regex("\\.m3u8(?=($|\\?))", RegexOption.IGNORE_CASE), ".ts")
                    Toast.makeText(this@PlayerActivity, "Nouvel essai en format TS…", Toast.LENGTH_SHORT).show()
                    prepareAndPlay(alternate, MimeTypes.VIDEO_MP2T)
                    return
                }
                lastDiagnostic = createPlaybackDiagnostic(error)
                showFailure(error)
            }
        })
        prepareAndPlay(url, normalizeMime(mime, url))
    }

    private fun prepareAndPlay(targetUrl: String, targetMime: String) {
        url = targetUrl
        val builder = MediaItem.Builder().setUri(Uri.parse(targetUrl)).setMediaId(targetUrl).setMimeType(targetMime)
        if (isLive) builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.03f).build())
        exoPlayer.setMediaItem(builder.build(), if (!isLive) startPositionMs else C.TIME_UNSET)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun normalizeMime(input: String, targetUrl: String): String = when {
        targetUrl.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
        targetUrl.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
        targetUrl.contains(".ts", true) -> MimeTypes.VIDEO_MP2T
        targetUrl.contains(".mkv", true) -> MimeTypes.VIDEO_MATROSKA
        targetUrl.contains(".webm", true) -> MimeTypes.VIDEO_WEBM
        else -> input.ifBlank { MimeTypes.VIDEO_MP4 }
    }

    private fun createPlaybackDiagnostic(error: PlaybackException): SavedDiagnostic? = runCatching {
        val causeChain = generateSequence<Throwable>(error) { it.cause }.map { "${it.javaClass.name}: ${it.message}" }.toList()
        val payload = JSONObject()
            .put("event", "player_error")
            .put("title", title)
            .put("url", DiagnosticManager.sanitizeUrl(url))
            .put("mime", normalizeMime(mime, url))
            .put("isLive", isLive)
            .put("retryAttempt", retryAttempt)
            .put("errorCode", error.errorCode)
            .put("errorCodeName", error.errorCodeName)
            .put("message", error.message)
            .put("causeChain", causeChain)
            .put("playbackState", exoPlayer.playbackState)
            .put("currentPositionMs", exoPlayer.currentPosition)
            .put("bufferedPositionMs", exoPlayer.bufferedPosition)
            .put("durationMs", exoPlayer.duration)
            .put("videoFormat", exoPlayer.videoFormat?.toString() ?: JSONObject.NULL)
            .put("audioFormat", exoPlayer.audioFormat?.toString() ?: JSONObject.NULL)
            .put("trackGroups", exoPlayer.currentTracks.toString())
        DiagnosticManager.save(this, "media3-exoplayer", payload)
    }.getOrNull()

    private fun createCastDiagnostic(event: String, code: Int) {
        runCatching {
            lastDiagnostic = DiagnosticManager.save(this, "google-cast-player", JSONObject()
                .put("event", event)
                .put("castErrorCode", code)
                .put("title", title)
                .put("url", DiagnosticManager.sanitizeUrl(url))
                .put("mime", normalizeMime(mime, url)))
            Toast.makeText(this, "Diagnostic Cast enregistré", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFailure(error: PlaybackException) {
        val path = lastDiagnostic?.displayPath ?: "échec de création du diagnostic"
        AlertDialog.Builder(this)
            .setTitle("Lecture impossible")
            .setMessage("Un diagnostic complet a été enregistré dans :\n$path\n\nCode : ${error.errorCodeName}")
            .setPositiveButton("Réessayer") { _, _ -> prepareAndPlay(url, normalizeMime(mime, url)) }
            .setNeutralButton("Partager le diagnostic") { _, _ -> lastDiagnostic?.let { DiagnosticManager.share(this, it) } }
            .setNegativeButton("Fermer") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun castTo(session: CastSession) {
        pendingCast = false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(url)
            .setContentType(normalizeMime(mime, url))
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata).build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(if (isLive) 0 else exoPlayer.currentPosition).build())
        exoPlayer.pause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && exoPlayer.isPlaying) enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); playerView.useController = !isInPictureInPictureMode }
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode) exoPlayer.pause() }
    override fun onDestroy() { castContext.sessionManager.removeSessionManagerListener(castListener, CastSession::class.java); exoPlayer.release(); super.onDestroy() }
}
