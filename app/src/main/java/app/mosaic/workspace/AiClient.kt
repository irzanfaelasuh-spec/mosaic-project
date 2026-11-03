package app.mosaic.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLException

data class AttachedFile(val name: String, val mime: String, val base64: String)

/** One message of the conversation that is sent to Gemini (Gemini roles: "user" / "model"). */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** Error whose message is already safe and readable for the user (never contains the API key). */
class AiException(message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Talks directly to the official Gemini REST API (generateContent) with the user's own API key.
 * No backend, proxy or server token is involved. Networking runs on a small background pool and
 * every callback is delivered on the main thread, so the UI never blocks.
 */
object AiClient {
    /** Latest generally available Flash model listed in the official Gemini API docs (see README). */
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val SYSTEM_PROMPT =
        "You are MOSAIC Assistant. Be helpful, clear, concise, and answer in the user's language. " +
            "The chat window shows plain text only, so avoid Markdown headings and tables; short dash lists are fine. " +
            "When analyzing an attachment, distinguish visible facts from assumptions."
    private const val DEFAULT_FILE_PROMPT = "Please inspect the attached file and summarize its contents."

    private const val MSG_NO_KEY =
        "Gemini API key is not set. Open MOSAIC → Settings → AI Connection, paste your key and tap SAVE."
    private const val MSG_BAD_KEY_FORMAT =
        "That does not look like a valid API key. Paste the full key from Google AI Studio without spaces."
    private const val MSG_BAD_MODEL =
        "Model name looks invalid. Use a name such as $DEFAULT_MODEL."
    private const val MSG_UNSUPPORTED =
        "Unsupported attachment. Supported: images (PNG, JPEG, WEBP, HEIC, HEIF), PDF, text/code files, ZIP, DOCX, XLSX and PPTX."

    private const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 180_000
    private const val MAX_OFFICE_ENTRY_BYTES = 3_000_000
    private const val MAX_ENTRY_CHARS = 80_000
    private const val MAX_OFFICE_ENTRY_CHARS = 200_000
    private const val MAX_ARCHIVE_CHARS = 650_000
    private const val MAX_TEXT_CHARS = 600_000
    private const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
    private const val MAX_TURNS = 40
    private const val MAX_HISTORY_CHARS = 200_000

    private val ARCHIVE_EXT = setOf("zip", "docx", "xlsx", "pptx")
    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "json", "xml", "html", "css", "js", "ts", "kt", "java", "py",
        "yml", "yaml", "properties", "gradle", "toml", "log", "ini", "sql", "sh"
    )
    private val INLINE_MIMES = setOf(
        "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "application/pdf"
    )
    private val EXT_MIME = mapOf(
        "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif"
    )
    private val ZIP_TEXT_REGEX = Regex(
        "\\.(txt|md|csv|json|xml|html|css|js|ts|kt|java|py|yml|yaml|properties|gradle|toml|log|ini|sql|sh)\$",
        RegexOption.IGNORE_CASE
    )
    private val MODEL_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,79}\$")
    private val KEY_PATTERN = Regex("AIza[0-9A-Za-z_\\-]{20,}")
    private val PARAGRAPH_END = Regex("</(?:w:p|a:p)>")
    private val XML_TAG = Regex("<[^>]*>")
    private val BLANK_LINES = Regex("\n{3,}")
    private val WHITESPACE = Regex("\\s+")

    private enum class Kind { ARCHIVE, INLINE, TEXT }

    private val io = Executors.newFixedThreadPool(2)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ public API

    /** Single-turn request (used by Translate). */
    fun generate(
        context: Context,
        prompt: String,
        attachment: AttachedFile? = null,
        callback: (Result<String>) -> Unit
    ) = chat(context, listOf(ChatTurn(true, prompt)), attachment, callback)

    /**
     * Multi-turn request. [history] must contain the whole conversation so far, ending with the new user turn.
     * The attachment (if any) is sent together with the last user turn only.
     */
    fun chat(
        context: Context,
        history: List<ChatTurn>,
        attachment: AttachedFile? = null,
        callback: (Result<String>) -> Unit
    ) {
        val app = context.applicationContext
        val snapshot = history.toList()
        io.execute {
            var keyForRedaction = ""
            val result: Result<String> = try {
                val store = SettingsStore.from(app)
                val key = store.geminiApiKeyOrNull() ?: throw AiException(MSG_NO_KEY)
                keyForRedaction = key
                Result.success(requestText(key, store.aiModel, snapshot, attachment))
            } catch (e: Throwable) {
                Result.failure(toFriendly(e, keyForRedaction))
            }
            main.post { callback(result) }
        }
    }

    /**
     * Sends one tiny real generateContent request to verify key + model.
     * [keyOverride] / [modelOverride] let the Settings screen test values that are typed but not saved yet.
     */
    fun testConnection(
        context: Context,
        keyOverride: String?,
        modelOverride: String?,
        callback: (Result<String>) -> Unit
    ) {
        val app = context.applicationContext
        val typedKey = keyOverride?.trim().orEmpty()
        val typedModel = modelOverride?.trim().orEmpty()
        if (typedKey.isNotEmpty() && SettingsStore.normalizeApiKey(typedKey) == null) {
            callback(Result.failure(AiException(MSG_BAD_KEY_FORMAT))); return
        }
        if (typedModel.isNotEmpty() && normalizeModel(typedModel) == null) {
            callback(Result.failure(AiException(MSG_BAD_MODEL))); return
        }
        io.execute {
            var keyForRedaction = ""
            val result: Result<String> = try {
                val store = SettingsStore.from(app)
                val key = (if (typedKey.isNotEmpty()) SettingsStore.normalizeApiKey(typedKey) else store.geminiApiKeyOrNull())
                    ?: throw AiException(MSG_NO_KEY)
                keyForRedaction = key
                val model = normalizeModel(typedModel) ?: store.aiModel
                val reply = requestText(key, model, listOf(ChatTurn(true, "Reply with exactly one word: OK")), null)
                Result.success("Connected. $model replied: ${reply.take(40)}")
            } catch (e: Throwable) {
                Result.failure(toFriendly(e, keyForRedaction))
            }
            main.post { callback(result) }
        }
    }

    /** Returns a clean model id (without a leading "models/") or null when the name is not acceptable. */
    fun normalizeModel(raw: String): String? {
        val m = raw.trim().removePrefix("models/")
        return if (MODEL_REGEX.matches(m)) m else null
    }

    /** "gemini-3.8-flash" -> "GEMINI 3.8 FLASH" (for the AI window header). */
    fun modelLabel(model: String): String = model.replace('-', ' ').uppercase(Locale.ROOT)

    /** True when the attachment is one of the types this client can really send. */
    fun isSupportedAttachment(file: AttachedFile): Boolean {
        val ext = extensionOf(file.name)
        return kindOf(ext, resolveMime(ext, file.mime)) != null
    }

    // ------------------------------------------------------------------ request / response

    private fun requestText(key: String, model: String, history: List<ChatTurn>, attachment: AttachedFile?): String {
        val payload = buildBody(history, attachment).toString().toByteArray(Charsets.UTF_8)
        var attempt = 0
        while (true) {
            attempt++
            val (code, body) = post(key, model, payload)
            if (code in 200..299) return parseSuccess(body)
            if ((code == 500 || code == 503) && attempt < 2) {
                Thread.sleep(1500)
                continue
            }
            throw httpError(code, body, key)
        }
    }

    private fun post(key: String, model: String, payload: ByteArray): Pair<Int, String> {
        val connection = URL(ENDPOINT_BASE + model + ":generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Official authentication header; the key is never put in the URL.
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Pair(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildBody(history: List<ChatTurn>, attachment: AttachedFile?): JSONObject {
        val turns = normalize(history)
        if (turns.isEmpty()) throw AiException("There is nothing to send.")
        val contents = JSONArray()
        turns.forEachIndexed { index, turn ->
            val parts = JSONArray()
            parts.put(JSONObject().put("text", turn.text))
            if (index == turns.lastIndex && attachment != null) {
                attachmentParts(attachment).forEach { parts.put(it) }
            }
            contents.put(JSONObject().put("role", if (turn.fromUser) "user" else "model").put("parts", parts))
        }
        return JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
        // Sampling parameters (temperature/top_p/top_k) are intentionally NOT sent: Google deprecated them
        // for Gemini 3.6+ models and the API ignores them (and will reject them in future generations).
    }

    /**
     * Makes the history valid for generateContent: starts with a user turn, roles alternate,
     * ends with a user turn (a trailing model turn is rejected with HTTP 400), and size stays bounded.
     */
    private fun normalize(history: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        for (turn in history) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            if (out.isEmpty() && !turn.fromUser) continue
            if (out.isNotEmpty() && out[out.lastIndex].fromUser == turn.fromUser) {
                out[out.lastIndex] = ChatTurn(turn.fromUser, out[out.lastIndex].text + "\n\n" + text)
            } else {
                out.add(ChatTurn(turn.fromUser, text))
            }
        }
        while (out.isNotEmpty() && !out[out.lastIndex].fromUser) out.removeAt(out.lastIndex)
        while (out.size > MAX_TURNS) out.removeAt(0)
        var chars = 0
        for (t in out) chars += t.text.length
        while (out.size > 1 && chars > MAX_HISTORY_CHARS) chars -= out.removeAt(0).text.length
        while (out.isNotEmpty() && !out[0].fromUser) out.removeAt(0)
        return out
    }

    private fun parseSuccess(raw: String): String {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw AiException("Gemini returned a response the app could not read.")
        }
        val first = json.optJSONArray("candidates")?.optJSONObject(0)
        if (first == null) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AiException(
                if (reason.isNotBlank()) "Gemini blocked this prompt ($reason). Try rephrasing it or removing the attachment."
                else "Gemini returned no answer."
            )
        }
        val sb = StringBuilder()
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                if (part.has("text")) sb.append(part.optString("text"))
            }
        }
        val answer = sb.toString().trim()
        val finish = first.optString("finishReason")
        if (answer.isNotEmpty()) {
            return if (finish == "MAX_TOKENS") "$answer\n\n[The answer was cut off by the length limit.]" else answer
        }
        throw AiException(
            when (finish) {
                "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY" ->
                    "Gemini declined to answer this request ($finish)."
                "MAX_TOKENS" -> "Gemini ran out of output length before producing text. Try a shorter request."
                "RECITATION" -> "Gemini stopped because the answer would repeat protected material."
                else -> if (finish.isNotBlank()) "Gemini returned an empty answer ($finish)." else "Gemini returned an empty answer."
            }
        )
    }

    private fun httpError(code: Int, body: String, key: String): AiException {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (e: Exception) {
            null
        }
        val serverMessage = error?.optString("message").orEmpty()
        val status = error?.optString("status").orEmpty()
        val keyRejected = serverMessage.contains("API key not valid", ignoreCase = true) ||
            serverMessage.contains("API key expired", ignoreCase = true) ||
            body.contains("API_KEY_INVALID")
        val detail = redact(serverMessage, key).replace(WHITESPACE, " ").trim().take(300)
        val message = when {
            keyRejected || code == 401 ->
                "Gemini rejected the API key (HTTP $code). Check or replace the key in Settings → AI Connection."
            code == 403 ->
                "Access denied (HTTP 403). The key may be restricted, disabled, or not allowed to use this model. $detail"
            code == 404 ->
                "Model not found (HTTP 404). The model name may be wrong or retired; check Settings → AI Connection → Model. $detail"
            code == 429 ->
                "Rate limit or quota reached (HTTP 429). Wait a bit and retry, switch the model in Settings, or check your quota in Google AI Studio."
            code == 400 && status == "FAILED_PRECONDITION" ->
                "The Gemini API is not available for this key, plan or region (HTTP 400). $detail"
            code == 400 ->
                "Gemini rejected the request (HTTP 400). $detail"
            code == 413 ->
                "The request is too large (HTTP 413). Try a smaller attachment."
            code in 500..599 ->
                "The Gemini service is temporarily unavailable (HTTP $code). Try again shortly."
            else ->
                "Gemini API error (HTTP $code). $detail"
        }
        return AiException(message.trim(), code)
    }

    private fun toFriendly(e: Throwable, key: String): Throwable = when (e) {
        is AiException -> e
        is SocketTimeoutException -> AiException("The request timed out. Check your connection and try again.")
        is UnknownHostException -> AiException("Cannot reach Google. Check your internet connection.")
        is SSLException -> AiException("The secure connection to Google failed. Check the device date/time and network.")
        is IOException -> AiException("Network error. ${redact(e.message.orEmpty(), key).take(160)}".trim())
        is OutOfMemoryError -> AiException("Not enough memory to send this request. Try a smaller attachment.")
        else -> AiException("Unexpected error. ${redact(e.message.orEmpty(), key).take(160)}".trim())
    }

    /** Removes the API key (and anything shaped like one) from text before it can be shown. */
    private fun redact(text: String, key: String): String {
        var out = text
        if (key.isNotEmpty()) out = out.replace(key, "[API key]")
        return out.replace(KEY_PATTERN, "[API key]")
    }

    // ------------------------------------------------------------------ attachments

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun resolveMime(ext: String, mime: String): String {
        val m = mime.lowercase(Locale.ROOT).substringBefore(';').trim()
        return when {
            m == "image/jpg" -> "image/jpeg"
            m.isNotEmpty() && m != "application/octet-stream" -> m
            else -> EXT_MIME[ext] ?: m
        }
    }

    private fun kindOf(ext: String, mime: String): Kind? = when {
        ext in ARCHIVE_EXT -> Kind.ARCHIVE
        mime in INLINE_MIMES -> Kind.INLINE
        ext in TEXT_EXT || mime.startsWith("text/") -> Kind.TEXT
        else -> null
    }

    private fun attachmentParts(file: AttachedFile): List<JSONObject> {
        val name = file.name.take(180).ifBlank { "attachment" }
        val ext = extensionOf(name)
        val mime = resolveMime(ext, file.mime)
        val kind = kindOf(ext, mime) ?: throw AiException(MSG_UNSUPPORTED)
        if (file.base64.isBlank()) throw AiException("The attached file is empty or could not be read.")
        if (file.base64.length / 4 * 3 > MAX_ATTACHMENT_BYTES) throw AiException("Attachment exceeds 8 MB.")
        return when (kind) {
            Kind.INLINE -> listOf(
                JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", file.base64))
            )
            Kind.ARCHIVE -> {
                val bytes = Base64.decode(file.base64, Base64.DEFAULT)
                listOf(JSONObject().put("text", "Contents extracted from $name:\n" + extractArchiveText(bytes, ext)))
            }
            Kind.TEXT -> {
                val bytes = Base64.decode(file.base64, Base64.DEFAULT)
                val text = String(bytes, Charsets.UTF_8).replace("\u0000", "").take(MAX_TEXT_CHARS)
                listOf(JSONObject().put("text", "File $name:\n$text"))
            }
        }
    }

    private fun isSafeEntryName(name: String): Boolean =
        name.split('/').none { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }

    private fun wantedEntry(ext: String, name: String): Boolean {
        if (!isSafeEntryName(name)) return false
        return when (ext) {
            "docx" -> name == "word/document.xml"
            "pptx" -> (name.startsWith("ppt/slides/slide") || name.startsWith("ppt/notesSlides/notesSlide")) && name.endsWith(".xml")
            "xlsx" -> name == "xl/workbook.xml" || name == "xl/sharedStrings.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
            else -> ZIP_TEXT_REGEX.containsMatchIn(name)
        }
    }

    private fun xmlToText(xml: String): String = xml
        .replace(PARAGRAPH_END, "\n")
        .replace(XML_TAG, "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(BLANK_LINES, "\n\n")

    /**
     * Reads supported text entries from a ZIP / DOCX / PPTX / XLSX in memory (java.util.zip only).
     * DOCX and PPTX text is pulled out of the document XML; XLSX sheets are passed on as raw sheet XML
     * (best-effort). Images, charts, formatting and layout are not interpreted.
     */
    private fun extractArchiveText(bytes: ByteArray, ext: String): String {
        val sb = StringBuilder()
        val stripXml = ext == "docx" || ext == "pptx"
        val office = ext != "zip"
        val entryCap = if (office) MAX_OFFICE_ENTRY_BYTES else MAX_ENTRY_BYTES
        val charCap = if (stripXml) MAX_OFFICE_ENTRY_CHARS else MAX_ENTRY_CHARS
        var inflated = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                val buf = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val wanted = !entry.isDirectory && wantedEntry(ext, name)
                    val out = if (wanted) ByteArrayOutputStream() else null
                    var size = 0
                    var tooBig = false
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        inflated += n
                        if (inflated > MAX_INFLATED_BYTES) throw AiException("This archive expands to too much data to read safely.")
                        size += n
                        if (out != null && !tooBig) {
                            if (size > entryCap) tooBig = true else out.write(buf, 0, n)
                        }
                    }
                    if (out == null || size == 0) continue
                    if (tooBig) {
                        sb.append("\n\n--- $name --- (skipped: file is too large)")
                        continue
                    }
                    var text = String(out.toByteArray(), Charsets.UTF_8)
                    if (stripXml) text = xmlToText(text)
                    text = text.replace("\u0000", "")
                    if (text.length > charCap) text = text.take(charCap) + "\n[…truncated]"
                    val part = "\n\n--- $name ---\n$text"
                    if (sb.length + part.length > MAX_ARCHIVE_CHARS) break
                    sb.append(part)
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            if (sb.isEmpty()) throw AiException("Could not read this archive. It may be damaged or password-protected.")
        }
        return if (sb.isEmpty()) "The archive contained no supported text files. It may contain only binary assets." else sb.toString()
    }
}
