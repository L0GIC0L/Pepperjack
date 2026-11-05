package io.github.l0gic0l.pepperjack.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Automatic measurements, as an oscilloscope offers them. */
enum class Measure(val label: String, val short: String) {
    MEAN("Mean (DC)", "Mean"),
    RMS("RMS", "RMS"),
    AC_RMS("AC RMS (DC removed)", "AC RMS"),
    PEAK_TO_PEAK("Peak to peak", "P-P"),
    MIN("Minimum", "Min"),
    MAX("Maximum", "Max"),
    FREQUENCY("Frequency", "Freq"),
    PERIOD("Period", "Period"),
    ;

    /** The unit a measurement of a waveform in [unit] is in. */
    fun unit(unit: String): String = when (this) {
        FREQUENCY -> "Hz"
        PERIOD -> "s"
        else -> unit
    }

    companion object {
        fun fromMask(mask: Int): List<Measure> = entries.filter { mask and (1 shl it.ordinal) != 0 }
        fun toggle(mask: Int, measure: Measure): Int = mask xor (1 shl measure.ordinal)
    }
}

/**
 * Statistics of a sampled waveform over a time window. Mean and RMS are weighted by time,
 * so unevenly spaced SPICE time points count for the time they cover, as a scope's do.
 */
class WaveStats(
    val min: Double,
    val max: Double,
    val mean: Double,
    val rms: Double,
    /** Repetition rate, or null when the window holds fewer than two full cycles. */
    val frequency: Double?,
) {
    val peakToPeak: Double get() = max - min
    val acRms: Double get() = sqrt(max(0.0, rms * rms - mean * mean))

    operator fun get(measure: Measure): Double? = when (measure) {
        Measure.MEAN -> mean
        Measure.RMS -> rms
        Measure.AC_RMS -> acRms
        Measure.PEAK_TO_PEAK -> peakToPeak
        Measure.MIN -> min
        Measure.MAX -> max
        Measure.FREQUENCY -> frequency
        Measure.PERIOD -> frequency?.let { 1 / it }
    }

    companion object {
        /**
         * Measures [values] sampled at [times] between [from] and [to] (both optional),
         * interpolating at the window's edges. NaN samples are skipped. With no [times]
         * (an operating point) the single value is a flat line.
         */
        fun of(
            times: DoubleArray?,
            values: DoubleArray,
            from: Double = Double.NEGATIVE_INFINITY,
            to: Double = Double.POSITIVE_INFINITY,
        ): WaveStats? {
            if (values.isEmpty()) return null
            if (times == null || times.size < 2 || values.size < 2) {
                val v = values.firstOrNull { !it.isNaN() } ?: return null
                return WaveStats(v, v, v, abs(v), null)
            }
            val n = min(times.size, values.size)
            val lo = min(from, to)
            val hi = max(from, to)
            val t = ArrayList<Double>(n + 2)
            val v = ArrayList<Double>(n + 2)
            for (i in 0 until n - 1) {
                val t0 = times[i]
                val t1 = times[i + 1]
                if (t1 < lo || t0 > hi) continue
                val v0 = values[i]
                val v1 = values[i + 1]
                fun at(time: Double) = if (t1 > t0) v0 + (v1 - v0) * (time - t0) / (t1 - t0) else v0
                val a = max(t0, lo)
                val b = min(t1, hi)
                if (t.isEmpty() || t.last() != a) {
                    t += a
                    v += at(a)
                }
                if (b > a) {
                    t += b
                    v += at(b)
                }
            }
            if (t.isEmpty()) return null
            var vmin = Double.POSITIVE_INFINITY
            var vmax = Double.NEGATIVE_INFINITY
            for (x in v) if (!x.isNaN()) {
                vmin = min(vmin, x)
                vmax = max(vmax, x)
            }
            if (vmin > vmax) return null
            var area = 0.0
            var square = 0.0
            var span = 0.0
            for (i in 0 until t.size - 1) {
                val a = v[i]
                val b = v[i + 1]
                if (a.isNaN() || b.isNaN()) continue
                val dt = t[i + 1] - t[i]
                area += (a + b) / 2 * dt
                // Exact for a straight line between samples.
                square += (a * a + a * b + b * b) / 3 * dt
                span += dt
            }
            val mean: Double
            val rms: Double
            if (span > 0) {
                mean = area / span
                rms = sqrt(max(0.0, square / span))
            } else {
                mean = v.first { !it.isNaN() }
                rms = abs(mean)
            }
            return WaveStats(vmin, vmax, mean, rms, frequency(t, v, vmin, vmax))
        }

        /**
         * Rising crossings of the mid level, with a hysteresis of a tenth of the peak to peak
         * so noise near the level does not count twice.
         */
        private fun frequency(t: List<Double>, v: List<Double>, vmin: Double, vmax: Double): Double? {
            val pp = vmax - vmin
            if (pp <= 1e-12 * max(1.0, max(abs(vmin), abs(vmax)))) return null
            val mid = (vmin + vmax) / 2
            val low = mid - pp * 0.1
            var armed = false
            var first = Double.NaN
            var last = Double.NaN
            var count = 0
            for (i in 0 until t.size - 1) {
                val a = v[i]
                val b = v[i + 1]
                if (a.isNaN() || b.isNaN()) continue
                if (a <= low) armed = true
                if (armed && a < mid && b >= mid) {
                    val crossing = t[i] + (t[i + 1] - t[i]) * (mid - a) / (b - a)
                    if (count == 0) first = crossing
                    last = crossing
                    count++
                    armed = false
                }
            }
            if (count < 2 || last <= first) return null
            return (count - 1) / (last - first)
        }
    }
}
