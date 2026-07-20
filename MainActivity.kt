package fr.yoann.streamboxvision

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var routeButton: MediaRouteButton
    private lateinit var castContext: CastContext
    private var pendingCast: JSONObject? = null

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) { pendingCast?.let { loadCast(session, it) }; pendingCast = null; notifyCast(true, session.castDevice?.friendlyName.orEmpty()) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { pendingCast?.let { loadCast(session, it) }; pendingCast = null; notifyCast(true, session.castDevice?.friendlyName.orEmpty()) }
        override fun onSessionEnded(session: CastSession, error: Int) = notifyCast(false, "")
        override fun onSessionStartFailed(session: CastSession, error: Int) { pendingCast = null; saveCastDiagnostic("session_start_failed", error); notifyCast(false, "") }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { saveCastDiagnostic("session_resume_failed", error); notifyCast(false, "") }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        routeButton = findViewById(R.id.mediaRouteButton)
        castContext = CastContext.getSharedInstance(this)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, routeButton)
        castContext.sessionManager.addSessionManagerListener(castListener, CastSession::class.java)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString StreamBoxVision/9.0"
        }
        WebView.setWebContentsDebuggingEnabled(false)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return if (uri.scheme == "file" || uri.scheme == "https" || uri.scheme == "http") false else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    true
                }
            }
        }
        webView.addJavascriptInterface(PlayerBridge(), "StreamBoxPlayer")
        webView.addJavascriptInterface(CastBridge(), "StreamBoxCast")
        webView.addJavascriptInterface(DiagnosticsBridge(), "StreamBoxDiagnostics")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class PlayerBridge {
        @JavascriptInterface fun getCapabilities(): String = JSONObject().put("native", true).put("play", true).toString()
        @JavascriptInterface fun play(payloadJson: String): String = try {
            val p = JSONObject(payloadJson)
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra("url", p.getString("url")); putExtra("title", p.optString("title")); putExtra("subtitle", p.optString("subtitle")); putExtra("mime", p.optString("mime", "video/mp4")); putExtra("image", p.optString("image")); putExtra("isLive", p.optBoolean("isLive")); putExtra("startPositionMs", p.optLong("startPositionMs", 0L))
            }
            runOnUiThread { startActivity(intent) }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message).toString() }
    }

    inner class CastBridge {
        @JavascriptInterface fun getCapabilities(): String = JSONObject().put("native", true).put("cast", true).put("external", true).put("share", true).put("settings", false).toString()
        @JavascriptInterface fun requestSession(payloadJson: String): String = try {
            val p = JSONObject(payloadJson)
            runOnUiThread {
                val session = castContext.sessionManager.currentCastSession
                if (session?.isConnected == true) loadCast(session, p) else { pendingCast = p; routeButton.performClick() }
            }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message).toString() }
        @JavascriptInterface fun stop(): String { runOnUiThread { castContext.sessionManager.endCurrentSession(true) }; return JSONObject().put("ok", true).toString() }
        @JavascriptInterface fun openExternal(payloadJson: String): String = try {
            val p = JSONObject(payloadJson); val i = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(p.getString("url")), p.optString("mime", "video/*")); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runOnUiThread { startActivity(Intent.createChooser(i, "Ouvrir avec…")) }; JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message).toString() }
        @JavascriptInterface fun shareMedia(payloadJson: String): String = try {
            val p = JSONObject(payloadJson); val i = Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,p.getString("url")); putExtra(Intent.EXTRA_SUBJECT,p.optString("title")) }
            runOnUiThread { startActivity(Intent.createChooser(i, "Partager le flux")) }; JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message).toString() }
    }


    inner class DiagnosticsBridge {
        @JavascriptInterface fun getCapabilities(): String = JSONObject()
            .put("native", true)
            .put("automaticSave", true)
            .put("share", true)
            .toString()

        @JavascriptInterface fun saveReport(payloadJson: String): String = try {
            val payload = JSONObject(payloadJson)
            if (payload.has("url")) payload.put("url", DiagnosticManager.sanitizeUrl(payload.optString("url")))
            if (payload.has("currentSrc")) payload.put("currentSrc", DiagnosticManager.sanitizeUrl(payload.optString("currentSrc")))
            val diagnostic = DiagnosticManager.save(this@MainActivity, "webview", payload)
            runOnUiThread { Toast.makeText(this@MainActivity, "Diagnostic enregistré : ${diagnostic.displayPath}", Toast.LENGTH_LONG).show() }
            JSONObject().put("ok", true).put("name", diagnostic.name).put("path", diagnostic.displayPath).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
        }

        @JavascriptInterface fun shareLast(payloadJson: String): String = try {
            val payload = JSONObject(payloadJson)
            val diagnostic = DiagnosticManager.save(this@MainActivity, "manual-share", payload)
            runOnUiThread { DiagnosticManager.share(this@MainActivity, diagnostic) }
            JSONObject().put("ok", true).put("path", diagnostic.displayPath).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    private fun loadCast(session: CastSession, p: JSONObject) {
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, p.optString("title", "Lecture")); putString(MediaMetadata.KEY_SUBTITLE, p.optString("subtitle", "StreamBox Vision")); p.optString("image").takeIf { it.isNotBlank() }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(p.getString("url")).setContentType(p.optString("mime", "video/mp4")).setStreamType(if (p.optBoolean("isLive")) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(meta).build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(if (p.optBoolean("isLive")) 0 else p.optLong("startPositionMs",0)).build())
    }


    private fun saveCastDiagnostic(event: String, error: Int) {
        runCatching {
            val payload = JSONObject()
                .put("event", event)
                .put("castErrorCode", error)
                .put("pendingMedia", pendingCast ?: JSONObject.NULL)
            val diagnostic = DiagnosticManager.save(this, "google-cast", payload)
            runOnUiThread { Toast.makeText(this, "Erreur Cast — diagnostic : ${diagnostic.displayPath}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun notifyCast(connected: Boolean, name: String) {
        val json = JSONObject().put("connected", connected).put("connecting", false).put("deviceName", name).toString()
        runOnUiThread { webView.evaluateJavascript("window.onStreamBoxCastState(${JSONObject.quote(json)})", null) }
    }
}
