package com.bustime.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 음성 안내와 함께 쓰는 진동 */
object Buzz {
    private val PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)

    @Suppress("DEPRECATION")
    fun vibrate(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
            } else {
                v.vibrate(PATTERN, -1)
            }
        } catch (e: Exception) {
        }
    }
}
