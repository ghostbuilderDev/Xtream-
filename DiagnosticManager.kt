package fr.yoann.streamboxvision

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticManager {
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun deviceInfo(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("network", network)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
    }

    fun sanitizeUrl(raw: String): String {
        return try {
            val uri = Uri.parse(raw)
            val segments = uri.pathSegments.toMutableList()
            if (segments.size >= 3 && (segments[0] == "live" || segments[0] == "movie" || segments[0] == "series")) {
                segments[1] = "***USER***"
                segments[2] = "***PASSWORD***"
            }
            uri.buildUpon().path("/" + segments.joinToString("/")).build().toString()
        } catch (_: Exception) {
            raw.replace(Regex("/(live|movie|series)/[^/]+/[^/]+/"), "/$1/***USER***/***PASSWORD***/")
        }
    }

    fun buildReport(context: Context, source: String, payload: JSONObject): String {
        val report = JSONObject()
            .put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            .put("source", source)
            .put("device", deviceInfo(context))
            .put("payload", payload)
        return "STREAMBOX VISION — RAPPORT DIAGNOSTIC\n" +
            "Ne pas modifier ce fichier avant de l'envoyer pour analyse.\n\n" +
            report.toString(2) + "\n"
    }

    fun save(context: Context, source: String, payload: JSONObject): SavedDiagnostic {
        val text = buildReport(context, source, payload)
        val name = "streambox-diagnostic-${dateFormat.format(Date())}.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamBoxDiagnostics")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Impossible de créer le fichier diagnostic")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Impossible d'écrire le fichier diagnostic")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            SavedDiagnostic(name, uri, "Téléchargements/StreamBoxDiagnostics/$name")
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "StreamBoxDiagnostics").apply { mkdirs() }
            val file = File(dir, name).apply { writeText(text) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            SavedDiagnostic(name, uri, file.absolutePath)
        }
    }

    fun share(context: Context, diagnostic: SavedDiagnostic) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, diagnostic.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Diagnostic StreamBox ${diagnostic.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Partager le diagnostic"))
    }
}

data class SavedDiagnostic(val name: String, val uri: Uri, val displayPath: String)
