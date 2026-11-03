package app.mosaic.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log

class VoiceCaptureService : Service() {
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var active = false

    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMeter(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> if (!active) { startForegroundNow(); startMeter() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MOSAIC microphone test").setContentText("Microphone level is being measured")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, n)
    }

    private fun startMeter() {
        try {
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val audio = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone recorder initialization failed" }
            recorder = audio; audio.startRecording(); active = true; running = true
            worker = Thread {
                val data = ShortArray(1024)
                while (active && !Thread.currentThread().isInterrupted) {
                    val n = try { audio.read(data, 0, data.size) } catch (e: Exception) { Log.e(TAG, "Mic read error", e); break }
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) { val v = data[i] / 32768.0; sum += v * v }
                        val rms = kotlin.math.sqrt(sum / n).toFloat()
                        val level = (rms * 4.2f).coerceIn(0f, 1f)
                        FloatingService.publishMic(level)
                    }
                }
                if (active) stopSelf()
            }.apply { name = "MOSAIC-MicMeter"; priority = Thread.NORM_PRIORITY; start() }
        } catch (e: Exception) { Log.e(TAG, "Mic monitor start failed", e); stopSelf() }
    }

    private fun stopMeter() {
        active = false; running = false; worker?.interrupt(); worker = null
        try { recorder?.stop() } catch (_: Exception) { }; try { recorder?.release() } catch (_: Exception) { }; recorder = null
        FloatingService.publishMic(0f)
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Microphone test", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { stopMeter(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.mosaic.workspace.mic.START"
        const val ACTION_STOP = "app.mosaic.workspace.mic.STOP"
        private const val CHANNEL = "mosaic_mic"
        private const val ID = 5184
        private const val TAG = "MOSAIC-VoiceMeter"
        @Volatile var running = false; private set
    }
}
