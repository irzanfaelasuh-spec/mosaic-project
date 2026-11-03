package app.mosaic.workspace

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Reused FFT spectrum engine from the previous visualizer project. Audio is processed in memory only. */
class SpectrumEngine(private val prefs: SettingsStore) {
    private val size = 1024
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private var previous = FloatArray(prefs.barCount)

    fun process(samples: ShortArray, count: Int): FloatArray {
        if (count <= 0) return FloatArray(prefs.barCount)
        val n = min(size, count)
        for (i in 0 until size) {
            val sample = if (i < n) samples[i] / 32768f else 0f
            val window = (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
            re[i] = sample * window
            im[i] = 0f
        }
        fft()
        val bars = prefs.barCount
        if (previous.size != bars) previous = FloatArray(bars)
        val out = FloatArray(bars)
        val minBin = 2.0
        val maxBin = size / 2.0
        for (i in 0 until bars) {
            val low = (minBin * Math.pow(maxBin / minBin, i.toDouble() / bars)).toInt().coerceAtLeast(1)
            val high = (minBin * Math.pow(maxBin / minBin, (i + 1).toDouble() / bars)).toInt().coerceAtLeast(low + 1)
            var energy = 0.0
            var bins = 0
            for (bin in low until min(high, size / 2)) {
                val magnitude = sqrt((re[bin] * re[bin] + im[bin] * im[bin]).toDouble())
                energy += magnitude * magnitude
                bins++
            }
            val rms = if (bins == 0) 0.0 else sqrt(energy / bins)
            val normalized = (rms / (size * .5) * 15.0 * prefs.sensitivity).coerceIn(0.0, 1.0)
            val shaped = Math.pow(normalized, .72).toFloat()
            val factor = if (shaped > previous[i]) (.20f + (1f - prefs.smoothing) * .78f) else (.025f + (1f - prefs.smoothing) * .24f)
            out[i] = (previous[i] + (shaped - previous[i]) * factor).coerceIn(0f, 1f)
        }
        previous = out.copyOf()
        return out
    }

    private fun fft() {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val angle = -2.0 * PI / len
            val wLenRe = cos(angle).toFloat()
            val wLenIm = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < size) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe; wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
