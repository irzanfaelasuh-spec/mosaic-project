package app.mosaic.workspace

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AiClient {
    private val io = Executors.newSingleThreadExecutor()

    fun generate(
        context: Context,
        prompt: String,
        attachment: AttachedFile? = null,
        callback: (Result<String>) -> Unit
    ) {
        val endpoint = SettingsStore.from(context).apiEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) {
            callback(Result.failure(IllegalStateException("AI backend belum diatur. Isi URL backend di Settings → AI connection.")))
            return
        }
        io.execute {
            try {
                val urlText = if (endpoint.endsWith("/api/generate")) endpoint else "$endpoint/api/generate"
                val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (SettingsStore.from(context).apiToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${SettingsStore.from(context).apiToken}")
                }
                val json = JSONObject().put("text", prompt).put("model", "gemini-3.7-flash")
                attachment?.let {
                    json.put("fileName", it.name).put("mimeType", it.mime).put("fileBase64", it.base64)
                }
                connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("Backend HTTP $code: ${body.take(450)}")
                val result = JSONObject(body).optString("text").ifBlank { "Backend returned an empty response." }
                callback(Result.success(result))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }
}

data class AttachedFile(val name: String, val mime: String, val base64: String)
