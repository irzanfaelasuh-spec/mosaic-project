package app.mosaic.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ToolContentFactory {
    fun create(service: FloatingService, tool: String, id: Int): View {
        val content = when (tool) {
            "Calculator" -> calculator(service)
            "Translate" -> translate(service)
            "AI Helper" -> AiPanelView(service).also { service.registerAiPanel(it) }
            "Google Search" -> search(service)
            "Visualizer" -> visualizer(service)
            "Voice Meter" -> VoiceMeterView(service).also { service.registerVoicePanel(it) }
            "Subtitle" -> subtitles(service)
            "FPS Monitor" -> fps(service)
            "Notes" -> notes(service)
            "Stopwatch" -> stopwatch(service)
            "Timer" -> timer(service)
            "Unit Converter" -> converter(service)
            "QR Maker" -> qr(service)
            "Color Studio" -> colorStudio(service)
            "Battery Info" -> battery(service)
            "System Shortcuts" -> shortcuts(service)
            else -> calculator(service)
        }
        // Chat and microphone panels manage their own vertical layout.
        return if (tool == "AI Helper" || tool == "Voice Meter") content else wrap(service, content)
    }

    private fun shell(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 11), dp(c, 10), dp(c, 11), dp(c, 14))
    }
    private fun heading(c: Context, s: String) = TextView(c).apply { text = s; textSize = 14f; setTextColor(0xFFEAF1F3.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private fun hint(c: Context, s: String) = TextView(c).apply { text = s; textSize = 9f; setTextColor(0xFF8FA1AC.toInt()); setLineSpacing(dp(c, 2).toFloat(), 1f) }
    private fun edit(c: Context, placeholder: String, multiline: Boolean = false): EditText = EditText(c).apply {
        hint = placeholder; textSize = 12f; setTextColor(0xFFEAF1F3.toInt()); setHintTextColor(0xFF758895.toInt())
        background = bg(0xFF0D1722.toInt(), dp(c, 10)); setPadding(dp(c, 11), dp(c, 10), dp(c, 11), dp(c, 10))
        if (multiline) { minLines = 3; maxLines = 7; gravity = Gravity.TOP or Gravity.START } else { setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE }
    }
    private fun btn(c: Context, s: String, primary: Boolean = false, action: () -> Unit) = TextView(c).apply {
        text = s; textSize = 9f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (primary) 0xFF081416.toInt() else 0xFFE5EEF2.toInt())
        background = bg(if (primary) SettingsStore.from(c).accent() else 0xFF1B2A38.toInt(), dp(c, 10))
        setPadding(dp(c, 10), dp(c, 11), dp(c, 10), dp(c, 11)); isClickable = true; setOnClickListener { action() }
    }
    private fun bg(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    private fun addGap(c: Context, parent: LinearLayout, h: Int = 9) { parent.addView(View(c), LinearLayout.LayoutParams(1, dp(c, h))) }
    private fun addLabel(c: Context, parent: LinearLayout, text: String, top: Int = 8) { parent.addView(hint(c, text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, top) }) }
    private fun wrap(c: Context, inner: View): View = ScrollView(c).apply { isFillViewport = true; addView(inner, ViewGroup.LayoutParams(-1, -2)) }

    private fun calculator(c: Context): View {
        val root = shell(c)
        val display = TextView(c).apply { text = "0"; textSize = 30f; gravity = Gravity.END or Gravity.CENTER_VERTICAL; setTextColor(0xFFF2F7F8.toInt()); setPadding(dp(c, 10), dp(c, 12), dp(c, 10), dp(c, 12)); background = bg(0xFF0D1722.toInt(), dp(c, 12)); minHeight = dp(c, 76); maxLines = 2 }
        root.addView(display, LinearLayout.LayoutParams(-1, -2))
        var expression = ""
        val keys = arrayOf("C", "(", ")", "÷", "7", "8", "9", "×", "4", "5", "6", "−", "1", "2", "3", "+", "%", "0", ".", "=")
        val grid = GridLayout(c).apply { columnCount = 4 }
        keys.forEach { key ->
            val b = btn(c, key, key == "=") {
                when (key) {
                    "C" -> expression = ""
                    "=" -> try { expression = ExpressionParser(expression.replace("×", "*").replace("÷", "/").replace("−", "-")).parse().let { if (it.isFinite()) format(it) else "Error" } } catch (_: Exception) { expression = "Error" }
                    "±" -> expression = if (expression.startsWith("-")) expression.drop(1) else "-$expression"
                    else -> { if (expression == "Error") expression = ""; expression += key }
                }
                display.text = expression.ifBlank { "0" }
            }
            val params = GridLayout.LayoutParams().apply { width = 0; height = dp(c, 47); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3)) }
            grid.addView(b, params)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 8) })
        addLabel(c, root, "Supports parentheses, decimals, remainder (%) and negative values.")
        return root
    }

    private fun translate(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Translate"))
        addLabel(c, root, "FROM ANY LANGUAGE  →  TARGET LANGUAGE", 4)
        val target = edit(c, "Target language (for example: Indonesian)")
        val source = edit(c, "Enter text to translate", true)
        root.addView(target, LinearLayout.LayoutParams(-1, dp(c, 43)).apply { topMargin = dp(c, 10) })
        root.addView(source, LinearLayout.LayoutParams(-1, dp(c, 112)).apply { topMargin = dp(c, 8) })
        val result = TextView(c).apply { text = "Translation appears here."; textSize = 12f; setTextColor(0xFFDCE8EC.toInt()); setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11)); background = bg(0xFF0D1722.toInt(), dp(c, 10)); minHeight = dp(c, 85) }
        root.addView(btn(c, "TRANSLATE", true) {
            val text = source.text.toString().trim(); val lang = target.text.toString().trim().ifBlank { "English" }
            if (text.isBlank()) { result.text = "Enter some text first." } else {
                result.text = "Translating…"
                AiClient.generate(c, "Translate the following text into $lang. Return only the translation and preserve tone.\n\n$text") { r ->
                    Handler(Looper.getMainLooper()).post { result.text = r.getOrElse { "Translation failed: ${it.message}" } }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 9) })
        root.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 9) })
        addLabel(c, root, "Requires the MOSAIC AI backend URL in Settings.")
        return root
    }

    private fun search(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Google Search"))
        addLabel(c, root, "Search the web without leaving your current input flow.", 4)
        val q = edit(c, "What are you looking for?")
        root.addView(q, LinearLayout.LayoutParams(-1, dp(c, 46)).apply { topMargin = dp(c, 13) })
        root.addView(btn(c, "SEARCH GOOGLE", true) { openUrl(c, "https://www.google.com/search?q=" + URLEncoder.encode(q.text.toString(), "UTF-8")) }, LinearLayout.LayoutParams(-1, dp(c, 44)).apply { topMargin = dp(c, 9) })
        listOf("Google", "YouTube", "Google Maps", "Wikipedia").forEach { name ->
            root.addView(btn(c, "OPEN $name") {
                val url = when (name) { "Google" -> "https://www.google.com"; "YouTube" -> "https://m.youtube.com"; "Google Maps" -> "https://maps.google.com"; else -> "https://www.wikipedia.org" }
                openUrl(c, url)
            }, LinearLayout.LayoutParams(-1, dp(c, 39)).apply { topMargin = dp(c, 7) })
        }
        return root
    }

    private fun visualizer(service: FloatingService): View {
        val c: Context = service
        val root = shell(c)
        root.addView(heading(c, "Audio Visualizer"))
        addLabel(c, root, "LIVE PLAYBACK CAPTURE  /  FFT ENGINE", 4)
        val view = SpectrumView(c)
        service.registerSpectrumPanel(view)
        root.addView(view, LinearLayout.LayoutParams(-1, dp(c, 130)).apply { topMargin = dp(c, 12) })
        root.addView(btn(c, if (AudioCaptureService.running) "CAPTURE ACTIVE" else "START PLAYBACK CAPTURE", true) {
            service.requestPlaybackCapture()
        }, LinearLayout.LayoutParams(-1, dp(c, 43)).apply { topMargin = dp(c, 10) })
        root.addView(btn(c, "STOP CAPTURE") { AudioCaptureService.requestStop(c) }, LinearLayout.LayoutParams(-1, dp(c, 39)).apply { topMargin = dp(c, 7) })
        addLabel(c, root, "Android shares only audio from apps that allow playback capture. Protected apps can stay silent.")
        return root
    }

    private fun fps(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "FPS Monitor"))
        addLabel(c, root, "OVERLAY RENDER RATE  /  DEVICE DISPLAY", 4)
        val monitor = FpsMonitorView(c)
        root.addView(monitor, LinearLayout.LayoutParams(-1, dp(c, 165)).apply { topMargin = dp(c, 12) })
        addLabel(c, root, "The counter measures MOSAIC's own UI frame callbacks. It is not a guaranteed per-game FPS sensor. Refresh rate is read from the active display mode.")
        return root
    }

    private fun notes(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Quick Notes"))
        addLabel(c, root, "Saved locally on this device.", 4)
        val input = edit(c, "Write a note…", true).apply { setText(SettingsStore.from(c).savedNotes); minLines = 7 }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(c, 190)).apply { topMargin = dp(c, 12) })
        root.addView(btn(c, "SAVE NOTE", true) { SettingsStore.from(c).savedNotes = input.text.toString(); Toast.makeText(c, "Note saved", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 9) })
        root.addView(btn(c, "CLEAR NOTE") { input.setText(""); SettingsStore.from(c).savedNotes = "" }, LinearLayout.LayoutParams(-1, dp(c, 39)).apply { topMargin = dp(c, 7) })
        return root
    }

    private fun stopwatch(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Stopwatch"))
        val time = TextView(c).apply { text = "00:00.00"; textSize = 31f; gravity = Gravity.CENTER; setTextColor(0xFFE8F1F4.toInt()); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(time, LinearLayout.LayoutParams(-1, dp(c, 112)))
        val handler = Handler(Looper.getMainLooper())
        var running = false; var startedAt = 0L; var elapsed = 0L
        fun renderTime() { val millis = if (running) elapsed + SystemClock.elapsedRealtime() - startedAt else elapsed; val min = millis / 60000; val sec = (millis / 1000) % 60; val cs = (millis % 1000) / 10; time.text = String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs) }
        val tick = object : Runnable { override fun run() { if (running) { renderTime(); handler.postDelayed(this, 35) } } }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { running = false; handler.removeCallbacks(tick) }
        })
        root.addView(btn(c, "START / PAUSE", true) { if (running) { elapsed += SystemClock.elapsedRealtime() - startedAt; running = false; renderTime() } else { startedAt = SystemClock.elapsedRealtime(); running = true; handler.post(tick) } }, LinearLayout.LayoutParams(-1, dp(c, 43)))
        root.addView(btn(c, "RESET") { running = false; elapsed = 0; renderTime() }, LinearLayout.LayoutParams(-1, dp(c, 40)).apply { topMargin = dp(c, 8) })
        return root
    }

    private fun timer(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Countdown Timer"))
        val input = edit(c, "Seconds", false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("60") }
        val time = TextView(c).apply { text = "01:00"; textSize = 31f; gravity = Gravity.CENTER; setTextColor(0xFFE8F1F4.toInt()); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(c, 44)).apply { topMargin = dp(c, 12) })
        root.addView(time, LinearLayout.LayoutParams(-1, dp(c, 92)))
        var timer: CountDownTimer? = null
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { timer?.cancel(); timer = null }
        })
        root.addView(btn(c, "START TIMER", true) {
            val seconds = input.text.toString().toLongOrNull()?.coerceIn(1, 86400) ?: 60L
            timer?.cancel()
            timer = object : CountDownTimer(seconds * 1000, 250) {
                override fun onTick(ms: Long) { time.text = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60) }
                override fun onFinish() { time.text = "00:00"; Toast.makeText(c, "Timer finished", Toast.LENGTH_LONG).show() }
            }.start()
        }, LinearLayout.LayoutParams(-1, dp(c, 43)))
        root.addView(btn(c, "CANCEL") { timer?.cancel(); time.text = "00:00" }, LinearLayout.LayoutParams(-1, dp(c, 40)).apply { topMargin = dp(c, 8) })
        return root
    }

    private fun converter(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Unit Converter"))
        val modes = listOf("Kilometers → Miles", "Miles → Kilometers", "Kilograms → Pounds", "Pounds → Kilograms", "Celsius → Fahrenheit", "Fahrenheit → Celsius", "Centimeters → Inches", "Inches → Centimeters")
        val spinner = Spinner(c).apply { adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, modes) }
        val value = edit(c, "Enter value").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
        val output = TextView(c).apply { text = "Result"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFFEAF1F3.toInt()); background = bg(0xFF0D1722.toInt(), dp(c, 10)); minHeight = dp(c, 72) }
        root.addView(spinner, LinearLayout.LayoutParams(-1, dp(c, 45)).apply { topMargin = dp(c, 12) })
        root.addView(value, LinearLayout.LayoutParams(-1, dp(c, 45)).apply { topMargin = dp(c, 8) })
        root.addView(btn(c, "CONVERT", true) {
            val n = value.text.toString().toDoubleOrNull()
            if (n == null) output.text = "Enter a number" else {
                val result = when (spinner.selectedItemPosition) {
                    0 -> n * .621371; 1 -> n / .621371; 2 -> n * 2.20462; 3 -> n / 2.20462
                    4 -> n * 9 / 5 + 32; 5 -> (n - 32) * 5 / 9; 6 -> n / 2.54; else -> n * 2.54
                }
                output.text = "${format(result)}"
            }
        }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 9) })
        root.addView(output, LinearLayout.LayoutParams(-1, dp(c, 74)).apply { topMargin = dp(c, 10) })
        return root
    }

    private fun qr(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "QR Maker"))
        addLabel(c, root, "Generate a QR code from text or a URL.", 4)
        val input = edit(c, "Text or URL", true)
        root.addView(input, LinearLayout.LayoutParams(-1, dp(c, 78)).apply { topMargin = dp(c, 11) })
        val image = ImageView(c).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, LinearLayout.LayoutParams(-1, dp(c, 190)).apply { topMargin = dp(c, 10) })
        root.addView(btn(c, "GENERATE QR", true) {
            try {
                val text = input.text.toString().trim(); require(text.isNotEmpty()) { "Enter content first" }
                val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 500, 500)
                val bitmap = android.graphics.Bitmap.createBitmap(500, 500, android.graphics.Bitmap.Config.ARGB_8888)
                for (x in 0 until 500) for (y in 0 until 500) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                image.setImageBitmap(bitmap)
            } catch (e: Exception) { Toast.makeText(c, e.message ?: "Could not make QR", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 8) })
        return root
    }

    private fun colorStudio(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Color Studio"))
        addLabel(c, root, "Paste a HEX color to preview RGB and build your own palette.", 4)
        val hex = edit(c, "#7AD8C8").apply { setText("#7AD8C8"); setSingleLine(true) }
        val preview = View(c).apply { background = bg(Color.rgb(122,216,200), dp(c, 15)) }
        val result = TextView(c).apply { text = "RGB 122, 216, 200"; gravity = Gravity.CENTER; setTextColor(0xFFEAF1F3.toInt()); textSize = 13f }
        root.addView(hex, LinearLayout.LayoutParams(-1, dp(c, 45)).apply { topMargin = dp(c, 12) })
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(c, 100)).apply { topMargin = dp(c, 10) })
        root.addView(result, LinearLayout.LayoutParams(-1, dp(c, 34)))
        root.addView(btn(c, "APPLY COLOR", true) {
            try { val color = Color.parseColor(hex.text.toString().trim()); preview.setBackgroundColor(color); result.text = "RGB ${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}  ·  ARGB ${hex.text}" }
            catch (_: Exception) { result.text = "Invalid HEX value" }
        }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 8) })
        return root
    }

    private fun battery(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Battery Info"))
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val status = TextView(c).apply { text = "$level%"; textSize = 42f; gravity = Gravity.CENTER; setTextColor(0xFFE8F1F4.toInt()) }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(c, 105)))
        root.addView(TextView(c).apply { text = if (charging) "Charging / full" else "On battery"; gravity = Gravity.CENTER; textSize = 12f; setTextColor(if (charging) SettingsStore.from(c).accent() else 0xFF9BAAB4.toInt()) }, LinearLayout.LayoutParams(-1, dp(c, 30)))
        addLabel(c, root, "Power information is provided by Android BatteryManager. Temperature and voltage can vary by manufacturer.")
        root.addView(btn(c, "OPEN BATTERY SETTINGS") { openIntent(c, Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)) }, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 13) })
        return root
    }

    private fun shortcuts(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "System Shortcuts"))
        val entries = listOf(
            "Wi-Fi settings" to Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            "Bluetooth settings" to Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            "Display settings" to Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS),
            "Sound settings" to Intent(android.provider.Settings.ACTION_SOUND_SETTINGS),
            "App information" to Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")),
            "Battery optimization" to Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "Date & time" to Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
        )
        entries.forEach { (name, intent) -> root.addView(btn(c, name.uppercase()) { openIntent(c, intent) }, LinearLayout.LayoutParams(-1, dp(c, 40)).apply { topMargin = dp(c, 6) }) }
        return root
    }

    private fun subtitles(c: Context): View {
        val root = shell(c)
        root.addView(heading(c, "Speech Subtitles"))
        addLabel(c, root, "Live speech-to-text using the device recognition provider.", 4)
        val transcript = TextView(c).apply { text = "Subtitles will appear here…"; textSize = 14f; setTextColor(0xFFEAF1F3.toInt()); setPadding(dp(c, 12), dp(c, 13), dp(c, 12), dp(c, 13)); background = bg(0xFF0D1722.toInt(), dp(c, 10)); minHeight = dp(c, 120) }
        root.addView(transcript, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12) })
        var recognizer: SpeechRecognizer? = null
        var listening = false
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                listening = false
                try { recognizer?.cancel() } catch (_: Exception) { }
                try { recognizer?.destroy() } catch (_: Exception) { }
                recognizer = null
            }
        })
        val start = btn(c, "START LISTENING", true) {
            if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                transcript.text = "Microphone permission is required. Return to MOSAIC to grant it, then tap Start again."
                if (c is FloatingService) c.requestSpeechPermission()
            } else if (!SpeechRecognizer.isRecognitionAvailable(c)) { transcript.text = "No speech recognition service is available on this device." }
            else try {
                if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(c)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { transcript.text = "Listening…" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) { listening = false; transcript.append("\n[Recognition ended: $error]") }
                    override fun onResults(results: android.os.Bundle?) { val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if (!words.isNullOrBlank()) transcript.append("\n$words"); listening = false }
                    override fun onPartialResults(partialResults: android.os.Bundle?) { val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if (!words.isNullOrBlank()) transcript.text = words }
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                recognizer?.startListening(intent); listening = true
            } catch (e: Exception) { transcript.text = "Could not start recognition: ${e.message}" }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(c, 42)).apply { topMargin = dp(c, 9) })
        root.addView(btn(c, "STOP LISTENING") { if (listening) recognizer?.stopListening(); listening = false }, LinearLayout.LayoutParams(-1, dp(c, 40)).apply { topMargin = dp(c, 7) })
        addLabel(c, root, "This mode recognizes microphone speech. It does not automatically transcribe protected audio from another app.")
        return root
    }

    private fun openUrl(c: Context, url: String) = openIntent(c, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    private fun openIntent(c: Context, intent: Intent) {
        try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(intent) }
        catch (e: Exception) { Toast.makeText(c, "Unable to open: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun format(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.') else "Error"
}

class AiPanelView(private val host: FloatingService) : LinearLayout(host) {
    private val messages: LinearLayout
    private val input: EditText
    private val fileStatus: TextView
    private val thinking: ThinkingView
    private var attachment: AttachedFile? = null
    private var waiting = false
    private var recognizer: SpeechRecognizer? = null
    private var speech: TextToSpeech? = null
    private var lastResponse = ""

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(9), dp(10), dp(8))
        addView(TextView(host).apply { text = "GEMINI 3.7 FLASH  /  AI ASSISTANT"; textSize = 8f; setTextColor(SettingsStore.from(host).accent()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        speech = TextToSpeech(host) { status -> if (status == TextToSpeech.SUCCESS) speech?.language = Locale.getDefault() }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                try { recognizer?.cancel() } catch (_: Exception) { }
                try { recognizer?.destroy() } catch (_: Exception) { }
                recognizer = null
                try { speech?.stop(); speech?.shutdown() } catch (_: Exception) { }
                speech = null
            }
        })
        val scroll = ScrollView(host).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        messages = LinearLayout(host).apply { orientation = VERTICAL }
        messages.addView(bubble("Hi. Ask me something, or attach an image, document, or ZIP file.", false))
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(7) })
        thinking = ThinkingView(host).apply { visibility = GONE }
        addView(thinking, LinearLayout.LayoutParams(-1, dp(26)))
        fileStatus = TextView(host).apply { text = "No attachment"; textSize = 8f; setTextColor(0xFF91A3AD.toInt()) }
        addView(fileStatus, LinearLayout.LayoutParams(-1, dp(17)))
        val row = LinearLayout(host).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(host).apply { hint = "Message MOSAIC AI…"; textSize = 11f; setTextColor(0xFFEAF1F3.toInt()); setHintTextColor(0xFF748895.toInt()); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0D1722.toInt()); cornerRadius = dp(10).toFloat() }; setPadding(dp(10), dp(9), dp(10), dp(9)); maxLines = 3 }
        row.addView(input, LinearLayout.LayoutParams(0, dp(43), 1f))
        row.addView(TextView(host).apply { text = "MIC"; textSize = 8f; gravity = Gravity.CENTER; setTextColor(SettingsStore.from(host).accent()); setOnClickListener { startVoiceInput() } }, LinearLayout.LayoutParams(dp(34), dp(43)))
        row.addView(TextView(host).apply { text = "+"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(SettingsStore.from(host).accent()); setOnClickListener { host.openFilePicker() } }, LinearLayout.LayoutParams(dp(28), dp(43)))
        row.addView(TextView(host).apply { text = "SAY"; textSize = 8f; gravity = Gravity.CENTER; setTextColor(SettingsStore.from(host).accent()); setOnClickListener { speakLastResponse() } }, LinearLayout.LayoutParams(dp(34), dp(43)))
        row.addView(TextView(host).apply { text = "↑"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(0xFF071416.toInt()); background = bg(SettingsStore.from(host).accent(), dp(10)); setOnClickListener { send() } }, LinearLayout.LayoutParams(dp(38), dp(43)))
        addView(row, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(5) })
        addView(TextView(host).apply { text = "AI responses may be inaccurate. Requests go to your configured server."; textSize = 7.5f; setTextColor(0xFF72838E.toInt()); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(19)))
    }

    fun attach(file: AttachedFile) { attachment = file; fileStatus.text = "ATTACHED  /  ${file.name}" }

    private fun startVoiceInput() {
        if (host.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fileStatus.text = "Microphone permission required. Grant it, then tap MIC again."
            host.requestSpeechPermission()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(host)) {
            fileStatus.text = "Speech recognition is unavailable on this device."
            return
        }
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(host)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { fileStatus.text = "Listening… speak now." }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { fileStatus.text = "Processing voice input…" }
                override fun onError(error: Int) { fileStatus.text = "Voice input stopped ($error). Tap MIC to retry." }
                override fun onResults(results: android.os.Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (words.isNotBlank()) input.setText(listOf(input.text.toString().trim(), words).filter { it.isNotBlank() }.joinToString(" "))
                    fileStatus.text = if (words.isBlank()) "No speech recognized." else "Voice input added."
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!words.isNullOrBlank()) fileStatus.text = "Hearing: $words"
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) { }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            recognizer?.startListening(intent)
        } catch (e: Exception) { fileStatus.text = "Voice input failed: ${e.message}" }
    }

    private fun speakLastResponse() {
        if (lastResponse.isBlank()) { fileStatus.text = "No AI response to read aloud yet."; return }
        try {
            val engine = speech
            if (engine == null) fileStatus.text = "Text-to-speech is still initializing. Tap SAY again shortly."
            else engine.speak(lastResponse, TextToSpeech.QUEUE_FLUSH, null, "mosaic_ai_response")
        } catch (e: Exception) { fileStatus.text = "Speech output failed: ${e.message}" }
    }

    private fun send() {
        if (waiting) return
        val prompt = input.text.toString().trim()
        if (prompt.isBlank() && attachment == null) return
        input.setText("")
        messages.addView(bubble(prompt.ifBlank { "Please inspect the attached file." }, true))
        waiting = true; thinking.visibility = VISIBLE; thinking.start()
        val file = attachment; attachment = null; fileStatus.text = "No attachment"
        val transcript = (0 until messages.childCount)
            .mapNotNull { (messages.getChildAt(it) as? TextView)?.text?.toString() }
            .joinToString("\n\n")
        val effectivePrompt = if (prompt.isBlank()) "Please inspect this attachment and explain its contents.\n\n$transcript" else transcript
        AiClient.generate(host, effectivePrompt, file) { result ->
            Handler(Looper.getMainLooper()).post {
                thinking.stop(); thinking.visibility = GONE; waiting = false
                lastResponse = result.getOrElse { "Request failed: ${it.message}" }
                messages.addView(bubble(lastResponse, false))
            }
        }
    }

    private fun bubble(text: String, user: Boolean) = TextView(host).apply {
        this.text = (if (user) "YOU\n" else "MOSAIC AI\n") + text
        textSize = 10.5f; setTextColor(if (user) 0xFFEAF1F3.toInt() else 0xFFC8D8DD.toInt())
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = bg(if (user) 0xFF1D3741.toInt() else 0xFF111E2A.toInt(), dp(10))
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun bg(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class ThinkingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SettingsStore.from(context).accent(); strokeWidth = dp(2f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SettingsStore.from(context).accent(); textSize = dp(8.5f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private var phase = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { phase += .24f; invalidate(); handler.postDelayed(this, 32) } }
    fun start() { handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() { handler.removeCallbacks(tick) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val mid = height / 2f
        canvas.drawText("THINKING", dp(2f), mid + dp(3f), textPaint)
        val left = dp(67f)
        for (line in 0..2) {
            val path = Path()
            var first = true
            var x = left.toInt()
            while (x <= width) {
                val progress = (x - left) / max(1f, w - left)
                val y = mid + sin(progress * 6f * Math.PI.toFloat() + phase + line * .7f) * (3f + line * 1.3f)
                if (first) { path.moveTo(x.toFloat(), y); first = false } else path.lineTo(x.toFloat(), y)
                x += 3
            }
            paint.alpha = 170 - line * 42
            canvas.drawPath(path, paint)
        }
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}

class SpectrumView(context: Context) : View(context) {
    private val prefs = SettingsStore.from(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values = FloatArray(prefs.barCount)
    private var peaks = FloatArray(prefs.barCount)
    init { refreshVisualSettings() }
    fun refreshVisualSettings() {
        setLayerType(if (prefs.glow) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE, null)
        invalidate()
    }
    fun setValues(next: FloatArray) {
        val copy = next.copyOf()
        post {
            if (values.size != copy.size) { values = FloatArray(copy.size); peaks = FloatArray(copy.size) }
            copy.copyInto(values, endIndex = min(copy.size, values.size))
            invalidate()
        }
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = values.size.coerceAtLeast(1); val pad = dp(4f); val gap = min(dp(prefs.barGap.toFloat()), (width - 2 * pad) / (n * 4.5f)); val bw = ((width - 2 * pad - gap * (n - 1)) / n).coerceAtLeast(dp(1f)); val floor = height - dp(8f); val maxH = (height - dp(16f)) * prefs.barHeight / 100f
        paint.color = 0x445A7888; c.drawLine(pad, floor, width - pad, floor, paint)
        for (i in 0 until n) {
            val x = pad + i * (bw + gap); peaks[i] = max(values[i], peaks[i] - .012f)
            val h = max(dp(2f), maxH * values[i]); val hsv = floatArrayOf((165f - i.toFloat() / n * 90f + 360f) % 360f, .58f, 1f); val color = Color.HSVToColor(hsv)
            paint.shader = LinearGradient(x, floor - h, x, floor, color, Color.argb(210, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            if (prefs.glow) paint.setShadowLayer(dp(7f), 0f, 0f, color) else paint.clearShadowLayer()
            c.drawRoundRect(RectF(x, floor - h, x + bw, floor), bw / 2, bw / 2, paint)
            paint.shader = null; paint.clearShadowLayer()
            if (prefs.peaks) { paint.color = color; c.drawLine(x, floor - max(dp(3f), maxH * peaks[i]) - dp(2f), x + bw, floor - max(dp(3f), maxH * peaks[i]) - dp(2f), paint) }
        }
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
}

class VoiceMeterView(private val host: FloatingService) : LinearLayout(host) {
    private val meter: GradientMeter
    private val value: TextView
    init {
        orientation = VERTICAL; setPadding(dp(10), dp(11), dp(10), dp(12))
        addView(TextView(host).apply { text = "MIC INPUT MONITOR"; textSize = 8f; setTextColor(SettingsStore.from(host).accent()) })
        value = TextView(host).apply { text = "0%  /  SILENT"; textSize = 18f; setTextColor(0xFFEAF1F3.toInt()); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        addView(value, LayoutParams(-1, dp(75)))
        meter = GradientMeter(host); addView(meter, LayoutParams(-1, dp(22)))
        addView(TextView(host).apply { text = "GREEN  →  YELLOW  →  ORANGE  →  RED"; textSize = 7.5f; setTextColor(0xFF94A6AF.toInt()); gravity = Gravity.CENTER }, LayoutParams(-1, dp(25)))
        addView(TextView(host).apply { text = "START MICROPHONE TEST"; textSize = 9f; gravity = Gravity.CENTER; setTextColor(0xFF061417.toInt()); background = bg(SettingsStore.from(host).accent(), dp(10)); setOnClickListener { host.requestMicrophone() } }, LayoutParams(-1, dp(41)))
        addView(TextView(host).apply { text = "STOP TEST"; textSize = 9f; gravity = Gravity.CENTER; setTextColor(0xFFE7EDF0.toInt()); background = bg(0xFF1A2A37.toInt(), dp(10)); setOnClickListener { host.stopService(Intent(host, VoiceCaptureService::class.java)) } }, LayoutParams(-1, dp(39)).apply { topMargin = dp(7) })
    }
    fun setLevel(v: Float) { post { val n = v.coerceIn(0f,1f); meter.level = n; value.text = "${(n * 100).toInt()}%  /  ${when { n < .05f -> "SILENT"; n < .3f -> "LOW"; n < .65f -> "ACTIVE"; else -> "LOUD" }}" } }
    private fun bg(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class GradientMeter(context: Context) : View(context) {
    var level = 0f; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c); val r = RectF(0f, 0f, width.toFloat(), height.toFloat()); val rad = height / 2f
        p.color = 0xFF182532.toInt(); c.drawRoundRect(r, rad, rad, p)
        p.shader = LinearGradient(0f,0f,width.toFloat(),0f, intArrayOf(Color.rgb(66,210,130), Color.rgb(231,216,76), Color.rgb(245,151,69), Color.rgb(234,75,83)), null, Shader.TileMode.CLAMP)
        c.save(); c.clipRect(0f, 0f, width * level, height); c.drawRoundRect(r, rad, rad, p); c.restore(); p.shader = null
    }
}

class FpsMonitorView(context: Context) : LinearLayout(context), android.view.Choreographer.FrameCallback {
    private val fpsText: TextView
    private val refreshText: TextView
    private var frames = 0
    private var start = 0L
    private var currentFps = 0
    private var attached = false
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0D1722.toInt()); cornerRadius = dp(13).toFloat() }
        fpsText = TextView(context).apply { text = "-- FPS"; textSize = 35f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        refreshText = TextView(context).apply { text = "Display refresh: -- Hz"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xFF9AADB8.toInt()) }
        addView(fpsText, LayoutParams(-1, dp(85))); addView(refreshText, LayoutParams(-1, dp(26)))
        val rate = if (Build.VERSION.SDK_INT >= 30) context.display?.refreshRate ?: 60f else 60f
        refreshText.text = "Display refresh: ${"%.0f".format(rate)} Hz"
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; frames = 0; start = 0; android.view.Choreographer.getInstance().postFrameCallback(this) }
    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        frames++
        if (start == 0L) start = frameTimeNanos
        val elapsed = frameTimeNanos - start
        if (elapsed >= 1_000_000_000L) {
            currentFps = (frames * 1_000_000_000.0 / elapsed).toInt(); frames = 0; start = frameTimeNanos
            post {
                fpsText.text = "$currentFps FPS"
                fpsText.setTextColor(when { currentFps in 1..10 -> Color.rgb(242,86,82); currentFps in 11..20 -> Color.rgb(242,148,69); currentFps in 21..30 -> Color.rgb(237,207,72); currentFps >= 31 -> Color.rgb(93,209,143); else -> 0xFF9AADB8.toInt() })
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onDetachedFromWindow() { attached = false; android.view.Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private fun format(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.') else "Error"

private class ExpressionParser(private val input: String) {
    private var pos = 0
    fun parse(): Double { val value = expression(); skip(); require(pos == input.length); return value }
    private fun expression(): Double { var v = term(); while (true) { skip(); v = when (peek()) { '+' -> { pos++; v + term() }; '-' -> { pos++; v - term() }; else -> return v } } }
    private fun term(): Double { var v = factor(); while (true) { skip(); v = when (peek()) { '*' -> { pos++; v * factor() }; '/' -> { pos++; val d = factor(); require(d != 0.0); v / d }; '%' -> { pos++; v % factor() }; else -> return v } } }
    private fun factor(): Double { skip(); if (peek() == '+') { pos++; return factor() }; if (peek() == '-') { pos++; return -factor() }; if (peek() == '(') { pos++; val v = expression(); skip(); require(peek() == ')'); pos++; return v }; val start = pos; while (peek()?.let { it.isDigit() || it == '.' } == true) pos++; require(start < pos); return input.substring(start, pos).toDouble() }
    private fun skip() { while (peek()?.isWhitespace() == true) pos++ }
    private fun peek(): Char? = input.getOrNull(pos)
}
