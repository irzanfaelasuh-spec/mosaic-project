package app.mosaic.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var active = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SettingsStore

    override fun onCreate() { super.onCreate(); prefs = SettingsStore.from(this); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                if (active) return START_NOT_STICKY
                val result = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                startAsForeground()
                if (data == null || result == 0) { stopSelf(); return START_NOT_STICKY }
                startCapture(result, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 42, Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MOSAIC visualizer is active").setContentText("Capturing permitted device playback")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID, n)
    }

    private fun startCapture(code: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(code, data) ?: error("MediaProjection could not be created")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopCapture(true); stopSelf() } }, main)
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val rate = 44_100
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minBytes > 0) { "AudioRecord buffer unavailable: $minBytes" }
            val audio = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBytes, 8192)).setAudioPlaybackCaptureConfig(config).build()
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord could not initialize" }
            recorder = audio
            val engine = SpectrumEngine(prefs)
            audio.startRecording(); active = true; running = true
            worker = Thread {
                val pcm = ShortArray(1024)
                while (active && !Thread.currentThread().isInterrupted) {
                    val count = try { audio.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) } catch (e: Exception) { Log.e(TAG, "Audio read failed", e); break }
                    if (count > 0) FloatingService.publishSpectrum(engine.process(pcm, count)) else if (count < 0) { Log.w(TAG, "AudioRecord returned $count"); break }
                }
                if (active) main.post { stopSelf() }
            }.apply { name = "MOSAIC-Spectrum"; priority = Thread.NORM_PRIORITY; start() }
        } catch (e: SecurityException) { Log.e(TAG, "Playback capture rejected by Android policy", e); stopSelf() }
        catch (e: Exception) { Log.e(TAG, "Playback capture failed", e); stopSelf() }
    }

    private fun stopCapture(projectionStopped: Boolean = false) {
        active = false; running = false
        worker?.interrupt(); worker = null
        try { recorder?.stop() } catch (_: Exception) { }; try { recorder?.release() } catch (_: Exception) { }; recorder = null
        if (!projectionStopped) try { projection?.stop() } catch (_: Exception) { }
        projection = null
        FloatingService.publishSpectrum(FloatArray(prefs.barCount))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Visualizer capture", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows while playback capture is active" })
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MOSAIC-AudioCapture"
        private const val CHANNEL_ID = "mosaic_capture"
        private const val NOTIFICATION_ID = 5183
        const val ACTION_START = "app.mosaic.workspace.audio.START"
        const val ACTION_STOP = "app.mosaic.workspace.audio.STOP"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        @Volatile var running = false; private set
        fun requestStop(context: Context) { context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP)) }
    }
}
