package app.mosaic.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SettingsStore
    private var handle: TextView? = null
    private var launcher: View? = null
    private var launcherParams: WindowManager.LayoutParams? = null
    private val windows = LinkedHashMap<Int, WindowEntry>()
    private val aiPanels = mutableSetOf<AiPanelView>()
    private val voicePanels = mutableSetOf<VoiceMeterView>()
    private val visualizerPanels = mutableSetOf<SpectrumView>()
    private var nextId = 1
    private var warningView: View? = null
    private var warningParams: WindowManager.LayoutParams? = null
    private var broadcastReceiver: BroadcastReceiver? = null

    data class WindowEntry(
        val id: Int,
        val tool: String,
        val root: LinearLayout,
        val frame: FrameLayout,
        val content: View,
        val params: WindowManager.LayoutParams,
        var normalWidth: Int,
        var normalHeight: Int,
        var minimized: Boolean = false,
        var maximized: Boolean = false
    )

    override fun onCreate() {
        super.onCreate()
        prefs = SettingsStore.from(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        current = this
        registerAttachmentReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWorkspace()
                return START_NOT_STICKY
            }
            ACTION_OPEN_MODULE -> {
                startAsForeground()
                if (SettingsCanOverlay()) {
                    attachHandle()
                    val name = intent.getStringExtra(EXTRA_MODULE) ?: "Calculator"
                    openModule(name)
                } else {
                    Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                startAsForeground()
                if (SettingsCanOverlay()) attachHandle()
                else {
                    Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MOSAIC workspace is active")
            .setContentText("Tap the edge handle to open your tools")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MOSAIC workspace",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while MOSAIC floating tools are available"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun attachHandle() {
        if (handle != null) return

        val v = TextView(this).apply {
            text = "│"
            textSize = 30f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(prefs.accent())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xEE1B2934.toInt(), 0xEE101923.toInt())
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(
                    dp(1),
                    Color.argb(
                        135,
                        prefs.accent().shr(16) and 255,
                        prefs.accent().shr(8) and 255,
                        prefs.accent() and 255
                    )
                )
            }
            elevation = dp(10).toFloat()
            setOnTouchListener { _, event -> handleTouch(event) }
            contentDescription = "MOSAIC floating tools handle. Drag vertically or tap to open."
        }

        val lp = WindowManager.LayoutParams(
            dp(27), dp(72),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(27)
            y = resources.displayMetrics.heightPixels / 2 - dp(36)
        }

        try {
            wm.addView(v, lp)
            handle = v
            handleParams = lp
        } catch (_: Exception) {
            Toast.makeText(this, "Overlay permission is not active", Toast.LENGTH_LONG).show()
        }
    }

    private var handleParams: WindowManager.LayoutParams? = null
    private var handleDownY = 0f
    private var handleStartY = 0
    private var handleMoved = false

    private fun handleTouch(e: MotionEvent): Boolean {
        val lp = handleParams ?: return true
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                handleDownY = e.rawY
                handleStartY = lp.y
                handleMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (e.rawY - handleDownY).toInt()
                if (kotlin.math.abs(delta) > dp(4)) handleMoved = true
                lp.y = (handleStartY + delta).coerceIn(
                    dp(24),
                    resources.displayMetrics.heightPixels - dp(100)
                )
                try {
                    wm.updateViewLayout(handle, lp)
                } catch (_: Exception) { }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!handleMoved) toggleLauncher()
                return true
            }
        }
        return true
    }

    private fun toggleLauncher() {
        if (launcher == null) showLauncher() else hideLauncher()
    }

    private fun showLauncher() {
        if (launcher != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(14), dp(13), dp(14), dp(13))
            elevation = dp(18).toFloat()
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val branding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        branding.addView(text("MOSAIC", 12f, TEXT, true))
        branding.addView(text("QUICK TOOL SHELF", 7.5f, MUTED, false))
        top.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        val count = text("${windows.size} WINDOWS", 8f, prefs.accent(), true)
        top.addView(count)
        root.addView(top, LinearLayout.LayoutParams(-1, dp(34)))

        val line = View(this).apply { setBackgroundColor(STROKE) }
        root.addView(
            line,
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(9)
                bottomMargin = dp(10)
            }
        )

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
        }

        val modules = listOf(
            "Calculator" to "CALC",
            "Translate" to "LANG",
            "AI Helper" to "AI",
            "Google Search" to "SEARCH",
            "Visualizer" to "AUDIO",
            "Voice Meter" to "MIC",
            "Subtitle" to "CAPTION",
            "FPS Monitor" to "FPS",
            "Notes" to "NOTES",
            "Stopwatch" to "CLOCK",
            "Timer" to "TIMER",
            "Unit Converter" to "UNITS",
            "QR Maker" to "QR",
            "Color Studio" to "COLOR",
            "Battery Info" to "POWER",
            "System Shortcuts" to "SYSTEM"
        )

        modules.forEachIndexed { index, (name, symbol) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    if (prefs.compactTiles) dp(9) else dp(12),
                    if (prefs.compactTiles) dp(9) else dp(12),
                    dp(10),
                    dp(10)
                )
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF192634.toInt(), 0xFF111B27.toInt())
                ).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), STROKE)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openModule(name)
                    hideLauncher()
                }
            }

            tile.addView(text(symbol, 8f, prefs.accent(), true))
            tile.addView(
                text(name, 11f, TEXT, true),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(5)
                }
            )

            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(if (prefs.compactTiles) 57 else 69)
                columnSpec = GridLayout.spec(index % 2, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(tile, lp)
        }

        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(314)))
        root.addView(
            text("Drag the edge bar to reposition it  ·  tap to close this shelf", 8f, MUTED, false).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, dp(25))
        )

        val screenW = resources.displayMetrics.widthPixels
        val params = overlayParams(
            min(screenW - dp(22), dp(330)),
            dp(402),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            x = (screenW - width - dp(30)).coerceAtLeast(dp(8))
            y = (handleParams?.y ?: dp(180)).coerceIn(
                dp(10),
                resources.displayMetrics.heightPixels - height - dp(80)
            )
        }

        try {
            wm.addView(root, params)
            launcher = root
            launcherParams = params
            root.alpha = 0f
            root.translationY = dp(12).toFloat()
            root.animate().alpha(1f).translationY(0f)
                .setDuration(if (prefs.reduceMotion) 0 else 210).start()
        } catch (_: Exception) {
            launcher = null
            launcherParams = null
        }
    }

    private fun hideLauncher() {
        val v = launcher ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) { }
        launcher = null
        launcherParams = null
    }

    fun openModule(tool: String) {
        hideLauncher()
        if (handle == null && SettingsCanOverlay()) attachHandle()

        val id = nextId++
        val content = ToolContentFactory.create(this, tool, id)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            elevation = dp(18).toFloat()
            clipToOutline = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF263A45.toInt(), 0xFF1B2936.toInt())
            ).apply {
                cornerRadius = dp(15).toFloat()
            }
        }

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(text(tool.uppercase(), 10f, TEXT, true))
        title.addView(text("MOSAIC WINDOW  /  ${id.toString().padStart(2, '0')}", 7f, MUTED, false))
        header.addView(title, LinearLayout.LayoutParams(0, -1, 1f))

        val red = dot(Color.rgb(242, 103, 109)) { closeWindow(id) }
        val yellow = dot(Color.rgb(239, 191, 93)) { minimizeWindow(id) }
        val green = dot(Color.rgb(105, 208, 157)) { maximizeWindow(id) }
        listOf(red, yellow, green).forEach {
            header.addView(
                it,
                LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginStart = dp(6)
                }
            )
        }

        root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        val frame = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))

        val resize = text("◢", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(80, 25, 43, 55), 6)
        }
        frame.addView(
            resize,
            FrameLayout.LayoutParams(dp(25), dp(23), Gravity.BOTTOM or Gravity.END)
        )
        root.addView(
            frame,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(7)
            }
        )

        val screenW = resources.displayMetrics.widthPixels
        val w = min(dp(prefs.defaultWidth), screenW - dp(18)).coerceAtLeast(dp(230))
        val h = min(
            dp(prefs.defaultHeight),
            resources.displayMetrics.heightPixels - dp(100)
        ).coerceAtLeast(dp(180))

        val params = overlayParams(w, h, 0).apply {
            x = ((screenW - w) / 2).coerceAtLeast(dp(6))
            y = dp(90 + (windows.size * 28))
                .coerceAtMost(resources.displayMetrics.heightPixels - h - dp(80))
        }

        val entry = WindowEntry(id, tool, root, frame, content, params, w, h)
        windows[id] = entry

        try {
            wm.addView(root, params)
            header.setOnTouchListener { _, event -> dragWindow(entry, event) }
            resize.setOnTouchListener { _, event -> resizeWindow(entry, event) }

            root.alpha = 0f
            root.scaleX = .97f
            root.scaleY = .97f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(if (prefs.reduceMotion) 0 else 180).start()

            if (windows.size >= 4 && !prefs.warningShown) showMultiWindowWarning()
        } catch (e: Exception) {
            windows.remove(id)
            Toast.makeText(
                this,
                "Could not create floating window: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0

    private fun dragWindow(entry: WindowEntry, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragX = event.rawX
                dragY = event.rawY
                startX = entry.params.x
                startY = entry.params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!entry.maximized) {
                    entry.params.x = (startX + event.rawX - dragX).toInt()
                        .coerceIn(0, resources.displayMetrics.widthPixels - entry.params.width)
                    entry.params.y = (startY + event.rawY - dragY).toInt()
                        .coerceIn(dp(8), resources.displayMetrics.heightPixels - dp(90))
                    update(entry.root, entry.params)
                }
                return true
            }
        }
        return true
    }

    private var resizeX = 0f
    private var resizeY = 0f
    private var resizeW = 0
    private var resizeH = 0

    private fun resizeWindow(entry: WindowEntry, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeX = event.rawX
                resizeY = event.rawY
                resizeW = entry.params.width
                resizeH = entry.params.height
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxW = resources.displayMetrics.widthPixels - dp(16)
                val maxH = resources.displayMetrics.heightPixels - dp(85)
                entry.params.width = (resizeW + (event.rawX - resizeX).toInt())
                    .coerceIn(dp(230), maxW)
                entry.params.height = (resizeH + (event.rawY - resizeY).toInt())
                    .coerceIn(dp(180), maxH)
                entry.normalWidth = entry.params.width
                entry.normalHeight = entry.params.height
                entry.minimized = false
                entry.maximized = false
                entry.frame.visibility = View.VISIBLE
                update(entry.root, entry.params)
                return true
            }
        }
        return true
    }

    private fun closeWindow(id: Int) {
        val e = windows.remove(id) ?: return
        try {
            wm.removeView(e.root)
        } catch (_: Exception) { }

        findDescendant(e.content, AiPanelView::class.java)?.let {
            aiPanels.remove(it)
        }

        findDescendant(e.content, VoiceMeterView::class.java)?.let {
            voicePanels.remove(it)
            if (voicePanels.isEmpty() && VoiceCaptureService.running) {
                stopService(Intent(this, VoiceCaptureService::class.java))
            }
        }

        findDescendant(e.content, SpectrumView::class.java)?.let {
            visualizerPanels.remove(it)
            if (visualizerPanels.isEmpty() && AudioCaptureService.running) {
                stopService(Intent(this, AudioCaptureService::class.java))
            }
        }
    }

    private fun <T : View> findDescendant(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val found = findDescendant(root.getChildAt(index), type)
                if (found != null) return found
            }
        }
        return null
    }

    private fun minimizeWindow(id: Int) {
        val e = windows[id] ?: return
        if (!e.minimized) {
            if (!e.maximized) {
                e.normalWidth = e.params.width
                e.normalHeight = e.params.height
            }
            e.frame.visibility = View.GONE
            e.params.height = dp(45)
            e.minimized = true
        } else {
            e.frame.visibility = View.VISIBLE
            e.params.height = if (e.maximized) e.normalHeight else e.normalHeight
            e.minimized = false
        }
        update(e.root, e.params)
    }

    private fun maximizeWindow(id: Int) {
        val e = windows[id] ?: return
        if (!e.maximized) {
            if (!e.minimized) {
                e.normalWidth = e.params.width
                e.normalHeight = e.params.height
            }
            e.params.width = resources.displayMetrics.widthPixels - dp(14)
            e.params.height = resources.displayMetrics.heightPixels - dp(100)
            e.params.x = dp(7)
            e.params.y = dp(25)
            e.maximized = true
            e.minimized = false
            e.frame.visibility = View.VISIBLE
        } else {
            e.params.width = e.normalWidth
            e.params.height = e.normalHeight
            e.params.x = ((resources.displayMetrics.widthPixels - e.params.width) / 2)
                .coerceAtLeast(dp(5))
            e.params.y = dp(90)
            e.maximized = false
        }
        update(e.root, e.params)
    }

    private fun showMultiWindowWarning() {
        if (warningView != null) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(18), dp(19), dp(17))
            background = panelBackground()
            elevation = dp(24).toFloat()
        }

        card.addView(text("MULTI-WINDOW NOTICE", 9f, prefs.accent(), true))
        card.addView(
            text("If there are too many windows you might experience a bit of lag.", 15f, TEXT, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        card.addView(
            text("Ignore keeps every window open. Accept closes all except one randomly selected window.", 10f, MUTED, false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ignore = button("IGNORE", Color.rgb(38, 55, 68)) {
            dismissWarning()
        }
        val accept = button("ACCEPT", prefs.accent(), darkText = true) {
            val survivor = windows.keys.toList().randomOrNull()
            windows.keys.toList().filter { it != survivor }.forEach { closeWindow(it) }
            dismissWarning()
        }

        row.addView(
            ignore,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
        )
        row.addView(
            accept,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) }
        )
        card.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        )

        val p = overlayParams(
            min(resources.displayMetrics.widthPixels - dp(32), dp(340)),
            -2,
            0
        ).apply {
            x = dp(16)
            y = resources.displayMetrics.heightPixels / 2 - dp(100)
        }

        try {
            wm.addView(card, p)
            warningView = card
            warningParams = p
            prefs.warningShown = true
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(160).start()
        } catch (_: Exception) {
            warningView = null
            warningParams = null
        }
    }

    private fun dismissWarning() {
        val v = warningView ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) { }
        warningView = null
        warningParams = null
    }

    fun registerAiPanel(panel: AiPanelView) {
        aiPanels.add(panel)
    }

    fun registerVoicePanel(panel: VoiceMeterView) {
        voicePanels.add(panel)
    }

    fun registerSpectrumPanel(panel: SpectrumView) {
        visualizerPanels.add(panel)
    }

    fun requestMicrophone() {
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_MIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }

    fun requestPlaybackCapture() {
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }

    fun requestSpeechPermission() {
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_SPEECH_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }

    fun openFilePicker() {
        startActivity(
            Intent(this, FilePickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun registerAttachmentReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val file = AttachedFile(
                    intent?.getStringExtra("name") ?: "attachment",
                    intent?.getStringExtra("mime") ?: "application/octet-stream",
                    intent?.getStringExtra("base64") ?: ""
                )
                aiPanels.forEach { it.attach(file) }
            }
        }

        broadcastReceiver = receiver
        val filter = IntentFilter(FilePickerActivity.ACTION_FILE_PICKED)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun stopWorkspace() {
        hideLauncher()
        dismissWarning()
        windows.keys.toList().forEach { closeWindow(it) }

        handle?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) { }
        }
        handle = null
        handleParams = null

        stopService(Intent(this, AudioCaptureService::class.java))
        stopService(Intent(this, VoiceCaptureService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopWorkspace()
        try {
            broadcastReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) { }
        current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayParams(
        w: Int,
        h: Int,
        extraFlags: Int
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun refreshAppearance() {
        windows.values.forEach { entry ->
            entry.root.background = panelBackground()
            findDescendant(entry.content, SpectrumView::class.java)
                ?.refreshVisualSettings()
        }
        handle?.setTextColor(prefs.accent())
    }

    private fun update(view: View, p: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, p)
        } catch (_: Exception) { }
    }

    private fun panelBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xF21A2734.toInt(), 0xF20B121C.toInt())
    ).apply {
        cornerRadius = dp(17).toFloat()
        setStroke(dp(1), Color.argb(110, 128, 160, 174))
        alpha = (prefs.windowOpacity * 2.55f).toInt().coerceIn(140, 255)
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun button(
        s: String,
        color: Int,
        darkText: Boolean = false,
        click: () -> Unit
    ) = TextView(this).apply {
        text = s
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (darkText) Color.rgb(7, 18, 20) else TEXT)
        background = rounded(color, 10)
        setOnClickListener { click() }
    }

    private fun dot(color: Int, click: () -> Unit) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.argb(60, 255, 255, 255))
        }
        isClickable = true
        setOnClickListener { click() }
    }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()

    private fun SettingsCanOverlay() =
        android.provider.Settings.canDrawOverlays(this)

    data class WindowEntrySnapshot(val tool: String, val id: Int)

    companion object {
        const val ACTION_START = "app.mosaic.workspace.START"
        const val ACTION_STOP = "app.mosaic.workspace.STOP"
        const val ACTION_OPEN_MODULE = "app.mosaic.workspace.OPEN_MODULE"
        const val EXTRA_MODULE = "module_name"

        private const val CHANNEL_ID = "mosaic_workspace"
        private const val NOTIFICATION_ID = 5182

        private const val TEXT = 0xFFEAF1F3.toInt()
        private const val MUTED = 0xFF91A3AD.toInt()
        private const val STROKE = 0xFF263747.toInt()

        @Volatile
        private var current: FloatingService? = null

        fun openModule(context: Context, name: String) {
            val i = Intent(context, FloatingService::class.java)
                .setAction(ACTION_OPEN_MODULE)
                .putExtra(EXTRA_MODULE, name)

            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Start MOSAIC workspace first",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        fun refreshWindows(context: Context) {
            current?.refreshAppearance()
        }

        fun publishSpectrum(data: FloatArray) {
            current?.visualizerPanels?.toList()?.forEach {
                it.setValues(data)
            }
        }

        fun publishMic(level: Float) {
            current?.voicePanels?.toList()?.forEach {
                it.setLevel(level)
            }
        }

        fun voicePermissionGranted(context: Context) {
            val i = Intent(context, VoiceCaptureService::class.java)
                .setAction(VoiceCaptureService.ACTION_START)

            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) { }
        }
    }
}
