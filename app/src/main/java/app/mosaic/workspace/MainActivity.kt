package app.mosaic.workspace

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var prefs: SettingsStore
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private var splashComplete = false
    private var currentPage = "HOME"
    private val handler = Handler(Looper.getMainLooper())

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread { toast(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku authorized" else "Shizuku permission was not granted") ; if (splashComplete) render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsStore.from(this)
        window.statusBarColor = Color.rgb(8, 13, 22)
        window.navigationBarColor = Color.rgb(8, 13, 22)
        window.decorView.systemUiVisibility = 0
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Exception) { }
        handleAction(intent)
        showSplash()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_REQUEST_MIC -> requestMicrophone()
            ACTION_REQUEST_CAPTURE -> requestPlaybackCapture()
            ACTION_REQUEST_SPEECH_PERMISSION -> requestSpeechPermissionOnly()
        }
    }

    override fun onResume() {
        super.onResume()
        if (splashComplete && ::root.isInitialized) render()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun showSplash() {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 23), Color.rgb(15, 31, 43), Color.rgb(9, 13, 22)))
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val graphic = SplashGraphic(this)
        stack.addView(graphic, LinearLayout.LayoutParams(dp(152), dp(152)))
        val name = TextView(this).apply {
            text = "MOSAIC"; textSize = 27f; letterSpacing = .24f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(237, 247, 245)); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        stack.addView(name, lp(-1, -2, top = 19))
        val sub = TextView(this).apply { text = "A QUIETLY POWERFUL WORKSPACE"; textSize = 9f; letterSpacing = .19f; gravity = Gravity.CENTER; setTextColor(Color.rgb(134, 163, 174)) }
        stack.addView(sub, lp(-1, -2, top = 7))
        val status = TextView(this).apply { text = "INITIALIZING ENVIRONMENT  ·  01 / 04"; textSize = 8f; letterSpacing = .12f; gravity = Gravity.CENTER; setTextColor(Color.rgb(122, 216, 200)) }
        stack.addView(status, lp(-1, -2, top = 35))
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        repeat(4) { i ->
            val dot = View(this).apply { background = oval(if (i == 0) prefs.accent() else Color.rgb(53, 70, 83)) }
            dots.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(4); marginEnd = dp(4) })
            if (!prefs.reduceMotion) dot.animate().alpha(.35f).setStartDelay(i * 120L).setDuration(700).withEndAction { dot.animate().alpha(1f).setDuration(700).start() }.start()
        }
        stack.addView(dots, lp(-1, dp(9), top = 12))
        frame.addView(stack, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        stack.alpha = 0f; stack.translationY = dp(18).toFloat()
        stack.animate().alpha(1f).translationY(0f).setDuration(850).setInterpolator(DecelerateInterpolator()).start()
        if (!prefs.reduceMotion) graphic.animate().rotationBy(360f).setDuration(8000).setInterpolator(android.view.animation.LinearInterpolator()).start()
        val phrases = listOf("INITIALIZING ENVIRONMENT  ·  01 / 04", "LOADING WORKSPACE MODULES  ·  02 / 04", "PREPARING FLOATING LAYERS  ·  03 / 04", "READY TO CONNECT  ·  04 / 04")
        phrases.forEachIndexed { i, phrase -> handler.postDelayed({ status.text = phrase }, (i * 1900L)) }
        handler.postDelayed({ splashComplete = true; render() }, 8000L)
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(17), dp(8), dp(17), dp(5)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply {
            text = "M"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(9, 16, 23)); background = rounded(prefs.accent(), 12)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brand.addView(label("MOSAIC", 14f, TEXT, true))
        brand.addView(label("MOBILE WORKSPACE  /  1.0", 8f, MUTED, false))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val status = TextView(this).apply { text = if (Settings.canDrawOverlays(this@MainActivity)) "OVERLAY READY" else "SETUP REQUIRED"; textSize = 8f; letterSpacing = .08f; setTextColor(if (Settings.canDrawOverlays(this@MainActivity)) prefs.accent() else AMBER); background = rounded(PANEL, 30); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(50)))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (prefs.setupDone) listOf("HOME", "SETTINGS", "ABOUT").forEach { page ->
            val b = TextView(this).apply {
                text = page; textSize = 9f; letterSpacing = .1f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(10), dp(13), dp(10))
                setTextColor(if (currentPage == page) TEXT else MUTED)
                background = if (currentPage == page) rounded(Color.rgb(29, 48, 54), 10) else transparent()
                setOnClickListener { currentPage = page; render() }
            }
            nav.addView(b, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7); bottomMargin = dp(6) })
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setPadding(0, dp(5), 0, dp(18)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = label("MOSAIC WORKSPACE  /  ANDROID 10+", 8f, Color.rgb(72, 90, 103), false).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9)) }
        root.addView(foot)
        setContentView(root)
        if (!prefs.setupDone) showSetup() else when (currentPage) { "SETTINGS" -> showSettings(); "ABOUT" -> showAbout(); else -> showHome() }
        root.alpha = 0f; root.animate().alpha(1f).setDuration(if (prefs.reduceMotion) 0 else 220).start()
    }

    private fun showSetup() {
        body.addView(label("FIRST-RUN CONFIGURATION", 9f, prefs.accent(), true))
        body.addView(label("Prepare your workspace.", 23f, TEXT, true), lp(-1, -2, top = 8))
        body.addView(label("A few Android access steps let MOSAIC float above your other apps. You stay in control of every permission.", 11f, MUTED, false), lp(-1, -2, top = 7, bottom = 13))
        val access = card()
        access.addView(label("ACCESS CHECKLIST", 9f, prefs.accent(), true))
        permissionRow(access, "Display over other apps", "Required for the edge handle and floating windows.", Settings.canDrawOverlays(this), "Allow") { openOverlaySettings() }
        permissionRow(access, "Shizuku service", "Advanced access. Start Shizuku separately, then approve MOSAIC.", isShizukuAuthorized(), "Connect") { setupShizuku() }
        permissionRow(access, "Notifications", "Shows the active-session notification while workspace runs.", notificationReady(), "Allow") { requestNotifications() }
        permissionRow(access, "Background battery policy", "Allow unrestricted battery use where your Android version offers it.", batteryExempt(), "Configure") { requestBatteryExemption() }
        permissionRow(access, "Microphone", "Asked only when you start Voice Meter or Speech Subtitles.", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "Later") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        body.addView(access)
        body.addView(label("Shizuku and battery exemption are optional for the basic tools. Android may still stop background work based on device-specific policies.", 9f, MUTED, false), lp(-1, -2, top = 10))
        body.addView(action(if (Settings.canDrawOverlays(this)) "NEXT  →" else "ALLOW OVERLAY FIRST  →") {
            if (!Settings.canDrawOverlays(this)) openOverlaySettings() else { prefs.setupDone = true; currentPage = "HOME"; render() }
        }, lp(-1, dp(49), top = 19))
        body.addView(label("You can change these permissions later in Settings.", 9f, MUTED, false).apply { gravity = Gravity.CENTER }, lp(-1, -2, top = 12))
    }

    private fun showHome() {
        body.addView(label("YOUR PERSONAL TOOL LAYER", 9f, prefs.accent(), true))
        body.addView(label("One screen. Many tools.", 25f, TEXT, true), lp(-1, -2, top = 7))
        body.addView(label("A compact desktop-inspired workspace that stays above your apps.", 11f, MUTED, false), lp(-1, -2, top = 7, bottom = 17))

        val hero = card()
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTop.addView(label("WORKSPACE SESSION", 9f, prefs.accent(), true), LinearLayout.LayoutParams(0, -2, 1f))
        heroTop.addView(label("FLOATING  /  MULTI-WINDOW", 8f, MUTED, false))
        hero.addView(heroTop)
        hero.addView(label("Your tools, wherever you are.", 19f, TEXT, true), lp(-1, -2, top = 10))
        hero.addView(label("Start the side handle, open any module, and keep it available while gaming, reading, or browsing.", 11f, MUTED, false), lp(-1, -2, top = 7, bottom = 15))
        val start = action("START WORKSPACE  →") { startWorkspace() }
        hero.addView(start, LinearLayout.LayoutParams(-1, dp(48)))
        val stop = secondaryAction("STOP WORKSPACE") { stopService(Intent(this, FloatingService::class.java)); toast("Workspace stopped") }
        hero.addView(stop, lp(-1, dp(42), top = 8))
        body.addView(hero)

        body.addView(label("MODULE LIBRARY", 9f, prefs.accent(), true), lp(-1, -2, top = 20, bottom = 10))
        val grid = arrayOf(
            "Calculator" to "NUM", "Translate" to "TXT", "AI Helper" to "AI",
            "Google Search" to "WEB", "Visualizer" to "FFT", "Voice Meter" to "MIC",
            "Subtitle" to "CC", "FPS Monitor" to "FPS", "Notes" to "DOC",
            "Stopwatch" to "TIME", "Timer" to "TMR", "Unit Converter" to "UNIT",
            "QR Maker" to "QR", "Color Studio" to "RGB", "Battery Info" to "PWR",
            "System Shortcuts" to "SYS"
        )
        grid.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { item ->
                val tile = card().apply { minimumHeight = dp(77); setPadding(dp(13), dp(11), dp(13), dp(11)) }
                tile.addView(label(item.second, 8f, prefs.accent(), true))
                tile.addView(label(item.first, 12f, TEXT, true), lp(-1, -2, top = 5))
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7); bottomMargin = dp(7) })
                tile.setOnClickListener { FloatingService.openModule(this, item.first) }
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            body.addView(row)
        }
        val access = card()
        access.addView(label("ACCESS CHECK", 9f, prefs.accent(), true))
        access.addView(accessRow("Overlay permission", Settings.canDrawOverlays(this)))
        access.addView(accessRow("Shizuku connection", isShizukuAuthorized()))
        access.addView(accessRow("Notifications", notificationReady()))
        access.addView(secondaryAction("OPEN ACCESS SETUP") { currentPage = "SETTINGS"; render() }, lp(-1, dp(40), top = 9))
        body.addView(access, lp(-1, -2, top = 9))
    }

    private fun showSettings() {
        body.addView(label("PREFERENCES", 9f, prefs.accent(), true))
        body.addView(label("Make it yours.", 24f, TEXT, true), lp(-1, -2, top = 7))
        body.addView(label("Theme, overlay behavior, access, and AI connection.", 11f, MUTED, false), lp(-1, -2, top = 6, bottom = 14))

        val access = card()
        access.addView(label("PERMISSIONS & ACCESS", 9f, prefs.accent(), true))
        permissionRow(access, "Draw over other apps", "Required for the floating handle and windows.", Settings.canDrawOverlays(this), "Open settings") { openOverlaySettings() }
        permissionRow(access, "Shizuku service", "Advanced shell access. Start Shizuku first, then authorize MOSAIC.", isShizukuAuthorized(), "Connect") { setupShizuku() }
        permissionRow(access, "Notifications", "Keeps an active-session notification visible.", notificationReady(), "Configure") { requestNotifications() }
        permissionRow(access, "Background battery policy", "May improve reliability; some manufacturers still apply their own limits.", batteryExempt(), "Configure") { requestBatteryExemption() }
        body.addView(access)

        val appearance = card()
        appearance.addView(label("APPEARANCE", 9f, prefs.accent(), true))
        appearance.addView(label("Accent palette", 11f, MUTED, false), lp(-1, -2, top = 9))
        val palette = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val colors = intArrayOf(Color.rgb(122,216,200), Color.rgb(137,165,255), Color.rgb(207,149,255), Color.rgb(246,174,111), Color.rgb(239,139,159))
        colors.forEachIndexed { index, color ->
            val swatch = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(if (prefs.themeIndex == index) 3 else 1), if (prefs.themeIndex == index) TEXT else Color.argb(70,255,255,255)) }; setOnClickListener { prefs.themeIndex = index; render() } }
            palette.addView(swatch, LinearLayout.LayoutParams(dp(33), dp(33)).apply { marginEnd = dp(12); topMargin = dp(9) })
        }
        appearance.addView(palette)
        addSettingSeek(appearance, "Window opacity", "${prefs.windowOpacity}%", 55, 100, prefs.windowOpacity) { prefs.windowOpacity = it; FloatingService.refreshWindows(this) }
        addSettingSeek(appearance, "Default window width", "${prefs.defaultWidth} dp", 230, 620, prefs.defaultWidth) { prefs.defaultWidth = it }
        addSettingSeek(appearance, "Default window height", "${prefs.defaultHeight} dp", 180, 760, prefs.defaultHeight) { prefs.defaultHeight = it }
        checkRow(appearance, "Compact tile layout", prefs.compactTiles) { prefs.compactTiles = it }
        checkRow(appearance, "Reduce motion", prefs.reduceMotion) { prefs.reduceMotion = it }
        body.addView(appearance, lp(-1, -2, top = 10))

        val visual = card()
        visual.addView(label("VISUALIZER ENGINE", 9f, prefs.accent(), true))
        addSettingSeek(visual, "Spectrum bars", "${prefs.barCount}", 12, 72, prefs.barCount) { prefs.barCount = it; FloatingService.refreshWindows(this) }
        addSettingSeek(visual, "Sensitivity", "${"%.1f".format(prefs.sensitivity)}×", 5, 35, (prefs.sensitivity * 10).toInt()) { prefs.sensitivity = it / 10f; FloatingService.refreshWindows(this) }
        addSettingSeek(visual, "Smoothing", "${(prefs.smoothing * 100).toInt()}%", 10, 95, (prefs.smoothing * 100).toInt()) { prefs.smoothing = it / 100f; FloatingService.refreshWindows(this) }
        addSettingSeek(visual, "Maximum bar height", "${prefs.barHeight}%", 30, 100, prefs.barHeight) { prefs.barHeight = it; FloatingService.refreshWindows(this) }
        addSettingSeek(visual, "Bar spacing", "${prefs.barGap} dp", 1, 9, prefs.barGap) { prefs.barGap = it; FloatingService.refreshWindows(this) }
        checkRow(visual, "Glow effects", prefs.glow) { prefs.glow = it; FloatingService.refreshWindows(this) }
        checkRow(visual, "Peak markers", prefs.peaks) { prefs.peaks = it; FloatingService.refreshWindows(this) }
        body.addView(visual, lp(-1, -2, top = 10))

        val ai = card()
        ai.addView(label("AI CONNECTION", 9f, prefs.accent(), true))
        ai.addView(label("Backend URL", 10f, MUTED, false), lp(-1, -2, top = 9))
        val endpoint = EditText(this).apply {
            setSingleLine(true); textSize = 11f; setTextColor(TEXT); setHintTextColor(MUTED); hint = "https://your-backend.example.com"
            setText(prefs.apiEndpoint); background = rounded(Color.rgb(10,18,27), 11); setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        ai.addView(endpoint, lp(-1, dp(46), top = 7))
        ai.addView(label("Backend access token (required)", 10f, MUTED, false), lp(-1, -2, top = 9))
        val token = EditText(this).apply {
            setSingleLine(true); textSize = 11f; setTextColor(TEXT); setHintTextColor(MUTED); hint = "MOSAIC_CLIENT_TOKEN"
            setText(prefs.apiToken); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = rounded(Color.rgb(10,18,27), 11); setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        ai.addView(token, lp(-1, dp(46), top = 7))
        ai.addView(label("The app sends requests to this backend. Keep GEMINI_API_KEY on the server, never inside the APK.", 10f, MUTED, false), lp(-1, -2, top = 7))
        ai.addView(action("SAVE AI CONNECTION") { prefs.apiEndpoint = endpoint.text.toString(); prefs.apiToken = token.text.toString(); toast("Backend settings saved") }, lp(-1, dp(42), top = 9))
        body.addView(ai, lp(-1, -2, top = 10))
    }

    private fun showAbout() {
        body.addView(label("ABOUT MOSAIC", 9f, prefs.accent(), true))
        body.addView(label("A workspace that follows you.", 23f, TEXT, true), lp(-1, -2, top = 7))
        body.addView(label("MOSAIC turns a small edge handle into a set of resizable floating tools. It is designed for quick access without replacing the app or game underneath.", 11f, MUTED, false), lp(-1, -2, top = 8, bottom = 15))
        val features = card()
        features.addView(label("INCLUDED MODULES", 9f, prefs.accent(), true))
        val names = listOf("Calculator", "Translate", "AI Helper", "Google Search", "Audio Visualizer", "Voice Meter", "Speech Subtitles", "FPS Monitor", "Notes", "Stopwatch", "Timer", "Unit Converter", "QR Maker", "Color Studio", "Battery Info", "System Shortcuts")
        names.forEachIndexed { i, n -> features.addView(label("${(i + 1).toString().padStart(2, '0')}   $n", 10f, TEXT, false), lp(-1, -2, top = 8)) }
        body.addView(features)
        val limits = card()
        limits.addView(label("ANDROID PLATFORM NOTES", 9f, prefs.accent(), true))
        limits.addView(label("• Shizuku requires its own service to be started by the user. It does not grant every permission automatically.\n• Playback capture only works when the source app permits it.\n• FPS Monitor measures MOSAIC's overlay render loop and reports display refresh rate; it is not a guaranteed per-game FPS sensor.\n• Speech recognition may use the device's recognition provider.\n• AI requires your own backend URL and a server-side Gemini API key.", 10f, MUTED, false), lp(-1, -2, top = 9))
        body.addView(limits, lp(-1, -2, top = 10))
        body.addView(label("Built for Android 10+  /  Version 1.0.0", 9f, MUTED, false), lp(-1, -2, top = 16))
    }

    private fun startWorkspace() {
        if (!Settings.canDrawOverlays(this)) { openOverlaySettings(); toast("Allow display over other apps, then tap Start again."); return }
        val intent = Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_START)
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent); toast("MOSAIC workspace started") }
        catch (e: Exception) { toast("Could not start workspace: ${e.message}") }
    }

    private fun setupShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) startActivity(launch) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                toast("Start Shizuku service, then return here")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) toast("Shizuku is already authorized (UID ${Shizuku.getUid()})")
            else Shizuku.requestPermission(SHIZUKU_REQUEST)
        } catch (e: Exception) { toast("Shizuku unavailable: ${e.message}") }
    }

    private fun isShizukuAuthorized(): Boolean = try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }

    private fun openOverlaySettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun notificationReady(): Boolean = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun requestNotifications() { if (Build.VERSION.SDK_INT >= 33 && !notificationReady()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) else toast("Notifications already allowed") }

    private fun batteryExempt(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun requestBatteryExemption() {
        if (batteryExempt()) { toast("Battery optimization is already disabled for MOSAIC"); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { startVoiceService(); return }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun requestSpeechPermissionOnly() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_SPEECH)
        else toast("Microphone permission is already granted")
    }

    private fun requestPlaybackCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try { startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE) }
        catch (e: Exception) { toast("Screen capture prompt unavailable: ${e.message}") }
    }

    @Deprecated("Activity result callback for MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_START)
                    .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode).putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                toast("Visualizer capture started")
            } else toast("Playback capture was cancelled")
        }
    }

    @Deprecated("Runtime permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            REQ_MIC -> if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceService() else toast("Microphone permission denied")
            REQ_NOTIF, REQ_SPEECH -> render()
        }
    }

    private fun startVoiceService() {
        try {
            val svc = Intent(this, VoiceCaptureService::class.java).setAction(VoiceCaptureService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            toast("Microphone monitor running")
        } catch (e: Exception) { toast("Microphone could not start: ${e.message}") }
    }

    private fun permissionRow(parent: LinearLayout, title: String, detail: String, ok: Boolean, button: String, click: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(10)) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 11f, TEXT, true))
        text.addView(label(detail, 9f, MUTED, false), lp(-1, -2, top = 3))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val action = TextView(this).apply { text = if (ok) "READY" else button.uppercase(); textSize = 8f; setTextColor(if (ok) prefs.accent() else AMBER); background = rounded(PANEL, 10); setPadding(dp(9), dp(8), dp(9), dp(8)); setOnClickListener { click() } }
        row.addView(action)
        parent.addView(row)
        parent.addView(View(this).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun accessRow(title: String, ok: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(3))
        addView(label(title, 10f, TEXT, false), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(if (ok) "READY" else "NEEDS SETUP", 8f, if (ok) prefs.accent() else AMBER, true))
    }

    private fun addSettingSeek(parent: LinearLayout, name: String, current: String, min: Int, max: Int, value: Int, change: (Int) -> Unit) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(name, 10f, TEXT, false), LinearLayout.LayoutParams(0, -2, 1f))
        val valueLabel = label(current, 9f, prefs.accent(), true)
        head.addView(valueLabel)
        parent.addView(head, lp(-1, -2, top = 12))
        val seek = SeekBar(this).apply { this.max = max - min; progress = (value - min).coerceIn(0, this.max); progressTintList = android.content.res.ColorStateList.valueOf(prefs.accent()); thumbTintList = android.content.res.ColorStateList.valueOf(prefs.accent()) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { val v = p + min; valueLabel.text = when (name) { "Sensitivity" -> "${"%.1f".format(v / 10f)}×"; "Smoothing" -> "$v%"; "Window opacity", "Maximum bar height" -> "$v%"; else -> if (name == "Default window width" || name == "Default window height" || name == "Bar spacing") "$v dp" else v.toString() }; if (fromUser) change(v) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        parent.addView(seek, lp(-1, dp(33)))
    }

    private fun checkRow(parent: LinearLayout, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(4)) }
        row.addView(label(text, 10f, TEXT, false), LinearLayout.LayoutParams(0, -2, 1f))
        val b = Button(this).apply { this.text = if (checked) "ON" else "OFF"; textSize = 8f; isAllCaps = false; setPadding(0, 0, 0, 0); minWidth = dp(54); minHeight = dp(32); background = rounded(if (checked) Color.rgb(31,60,55) else PANEL, 9); setTextColor(if (checked) prefs.accent() else MUTED); setOnClickListener { onChange(!checked); render() } }
        row.addView(b); parent.addView(row)
    }

    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(CARD, 17, STROKE) }
    private fun action(text: String, click: () -> Unit) = TextView(this).apply { this.text = text; textSize = 10f; letterSpacing = .09f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(7,17,21)); background = rounded(prefs.accent(), 12); setOnClickListener { click() } }
    private fun secondaryAction(text: String, click: () -> Unit) = TextView(this).apply { this.text = text; textSize = 9f; letterSpacing = .08f; gravity = Gravity.CENTER; setTextColor(TEXT); background = rounded(PANEL, 11, STROKE); setOnClickListener { click() } }
    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setLineSpacing(dp(2).toFloat(), 1f) }
    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun transparent() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private class SplashGraphic(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val cx = width / 2f; val cy = height / 2f; val scale = width / 150f
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * scale; p.color = Color.argb(55, 122,216,200)
            c.drawCircle(cx, cy, 55f * scale, p); c.drawCircle(cx, cy, 43f * scale, p)
            p.style = Paint.Style.FILL
            for (i in 0..3) for (j in 0..3) {
                val left = cx + (j - 1.5f) * 20f * scale - 7f * scale
                val top = cy + (i - 1.5f) * 20f * scale - 7f * scale
                val color = if ((i + j) % 3 == 0) Color.rgb(122,216,200) else Color.rgb(42,83,84)
                p.color = color
                c.drawRoundRect(RectF(left, top, left + 14f * scale, top + 14f * scale), 3f * scale, 3f * scale, p)
            }
            p.color = Color.rgb(223,245,240); p.style = Paint.Style.STROKE; p.strokeWidth = 2f * scale
            c.drawCircle(cx, cy, 67f * scale, p)
        }
    }

    companion object {
        const val ACTION_REQUEST_MIC = "app.mosaic.workspace.REQUEST_MIC"
        const val ACTION_REQUEST_CAPTURE = "app.mosaic.workspace.REQUEST_CAPTURE"
        const val ACTION_REQUEST_SPEECH_PERMISSION = "app.mosaic.workspace.REQUEST_SPEECH_PERMISSION"
        private const val SHIZUKU_REQUEST = 2201
        private const val REQ_MIC = 2202
        private const val REQ_NOTIF = 2203
        private const val REQ_CAPTURE = 2204
        private const val REQ_SPEECH = 2205
        private const val BG = 0xFF080D16.toInt()
        private const val CARD = 0xFF111B27.toInt()
        private const val PANEL = 0xFF172332.toInt()
        private const val STROKE = 0xFF263747.toInt()
        private const val TEXT = 0xFFE8F0F3.toInt()
        private const val MUTED = 0xFF8B9EAA.toInt()
        private const val AMBER = 0xFFF0B66D.toInt()
    }
}
