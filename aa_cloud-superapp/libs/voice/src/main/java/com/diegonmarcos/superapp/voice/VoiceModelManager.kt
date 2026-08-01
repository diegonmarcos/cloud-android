package com.diegonmarcos.superapp.voice

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import java.net.URL

/**
 * Resolves and lazily downloads Vosk models. The registry is data-driven from
 * build.json::voice.models (baked into [BuildConfig.VOICE_MODELS_B64]). A model
 * is fetched once into filesDir/vosk/<dir>, unzipped, and then reused fully
 * offline. All network/IO runs on a background executor; callbacks fire on that
 * thread (the caller marshals back to the main thread).
 */
object VoiceModelManager {

    private val io = Executors.newSingleThreadExecutor()

    private val models: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.VOICE_MODELS_B64, Base64.DEFAULT)))
    }

    /**
     * Map a BCP-47 tag (e.g. "pt-BR") to a registry key. Tries exact match,
     * then language-only ("pt-BR" -> "pt"), then any key sharing the language
     * ("en" -> "en-us"), finally the configured default.
     */
    fun resolveModelKey(languageTag: String?): String {
        val tag = languageTag?.lowercase().orEmpty()
        if (tag.isNotEmpty() && models.has(tag)) return tag
        val lang = tag.substringBefore('-')
        if (lang.isNotEmpty()) {
            if (models.has(lang)) return lang
            val keys = models.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == lang || k.startsWith("$lang-") || k.substringBefore('-') == lang) return k
            }
        }
        return BuildConfig.VOICE_DEFAULT_LANGUAGE
    }

    /** Every language key declared in build.json::voice.models, with whether its
     *  model is actually downloaded on this device yet. For settings/info screens. */
    fun registeredLanguages(context: Context): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val keys = models.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val dirName = models.optJSONObject(key)?.optString("dir").orEmpty()
            val downloaded = dirName.isNotBlank() && File(File(context.filesDir, "vosk"), "$dirName/am").isDirectory
            out.add(key to downloaded)
        }
        return out
    }

    /**
     * Ensure the model for [key] is present on disk, downloading+unzipping it on
     * first use. [onReady] receives the absolute model directory path; [onError]
     * receives a short message. Both run on the background thread.
     */
    fun ensureModel(ctx: Context, key: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val spec = models.optJSONObject(key) ?: models.optJSONObject(BuildConfig.VOICE_DEFAULT_LANGUAGE)
        if (spec == null) {
            onError("no voice model registered for '$key'")
            return
        }
        val dirName = spec.optString("dir")
        val url = spec.optString("url")
        val root = File(ctx.filesDir, "vosk")
        val modelDir = File(root, dirName)
        // A valid Vosk model directory always contains an "am" (acoustic model) subdir.
        if (File(modelDir, "am").isDirectory) {
            onReady(modelDir.absolutePath)
            return
        }
        io.execute {
            try {
                root.mkdirs()
                val tmpZip = File(root, "$dirName.zip.part")
                downloadTo(url, tmpZip)
                unzip(tmpZip, root)
                tmpZip.delete()
                if (File(modelDir, "am").isDirectory) onReady(modelDir.absolutePath)
                else onError("model unpacked but looks incomplete")
            } catch (e: Exception) {
                onError(e.message ?: "download failed")
            }
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, targetRoot: File) {
        val rootCanonical = targetRoot.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val outFile = File(targetRoot, entry.name)
                // zip-slip guard: never write outside the target root.
                if (!outFile.canonicalPath.startsWith(rootCanonical)) {
                    throw SecurityException("zip entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zin.copyTo(out, 64 * 1024) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
