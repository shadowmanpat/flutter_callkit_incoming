//  Modification by Signify in this file are under the following license:
//
//  Copyright 2024, Signify Holding
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//  The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.hiennv.flutter_callkit_incoming

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import java.util.Timer
import java.util.TimerTask

class CallkitSoundPlayerManager(private val context: Context) {

    private val tag = "RacfCallkitRing"

    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    private var ringtone: Ringtone? = null
    private var alertPlaysRemaining = 0
    private var alertData: Bundle? = null
    private val alertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var alertRepeat: Runnable? = null

    // Start Signify modification
    var isPlaying: Boolean = false
        private set
    // End Signify modification

    private var keepRingingForFullScreenIntent: Boolean = false
    private var screenReceiverRegistered = false

    inner class ScreenOffCallkitIncomingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The full-screen incoming call stays up when the screen locks.
            // Ringing continues until answer, decline, cancellation, timeout,
            // or the person lowers the ring volume. A screen-off broadcast
            // is not one of those.
        }
    }

    private var screenOffCallkitIncomingBroadcastReceiver =
        ScreenOffCallkitIncomingBroadcastReceiver()


    fun play(data: Bundle) {
        if (ringtone?.isPlaying == true || alertPlaysRemaining > 0) {
            return
        }
        this.isPlaying = true
        this.alertData = data
        this.alertPlaysRemaining = 2
        this.prepare()
        this.startAlert()
        this.playVibrator()

        if (!screenReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            context.registerReceiver(screenOffCallkitIncomingBroadcastReceiver, filter)
            screenReceiverRegistered = true
        }
    }

    fun stop() {
        this.isPlaying = false
        this.alertPlaysRemaining = 0
        this.alertRepeat?.let { alertHandler.removeCallbacks(it) }
        this.alertRepeat = null

        ringtone?.stop()
        vibrator?.cancel()
        ringtone = null
        vibrator = null
        if (screenReceiverRegistered) {
            try {
                context.unregisterReceiver(screenOffCallkitIncomingBroadcastReceiver)
            } catch (_: Exception) {
            }
            screenReceiverRegistered = false
        }
    }

    fun destroy() {
        stop()
    }

    private fun prepare() {
        ringtone?.stop()
        vibrator?.cancel()
    }

    private fun playVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!vibrationAllowed()) return

        val pattern = longArrayOf(0L, 180L, 120L, 180L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1),
                    audioAttrs
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1, audioAttrs)
            }
        } else {
            @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
        }
    }

    private fun notificationAudible(): Boolean {
        val manager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0) return false
        return !alertsSilenced()
    }

    private fun startAlert() {
        if (alertPlaysRemaining <= 0) return
        if (!notificationAudible()) {
            alertPlaysRemaining = 0
            return
        }
        val started = playSound(alertData)
        if (!started) {
            alertPlaysRemaining = 0
            return
        }
        alertPlaysRemaining -= 1
        if (alertPlaysRemaining <= 0) return
        val repeat = object : Runnable {
            override fun run() {
                if (!isPlaying || alertPlaysRemaining <= 0) return
                if (ringtone?.isPlaying == true) {
                    alertHandler.postDelayed(this, 200)
                    return
                }
                alertHandler.postDelayed({ startAlert() }, 800)
            }
        }
        alertRepeat = repeat
        alertHandler.postDelayed(repeat, 200)
    }

    private fun vibrationAllowed(): Boolean {
        val manager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        if (manager.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        return !alertsSilenced()
    }

    private fun alertsSilenced(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val filter = notifications.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun playSound(data: Bundle?): Boolean {
        if (!notificationAudible()) {
            Log.i(tag, "alert not started: silent, vibrate, zero notification volume, or DND")
            return false
        }
        val sound = data?.getString(
            CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH,
            ""
        )
        val uri = sound?.let { getRingtoneUri(it) }
        if (uri == null) {
            Log.w(tag, "alert resource was not resolved")
            return false
        }
        try {
            ringtone = RingtoneManager.getRingtone(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attribution = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                    .build()
                ringtone?.setAudioAttributes(attribution)
            } else {
                ringtone?.streamType = AudioManager.STREAM_NOTIFICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = false
            }
            ringtone?.play()
            if (ringtone?.isPlaying != true) {
                Log.w(tag, "alert play returned without playback")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(tag, "alert play failed", e)
            return false
        }
    }

    private fun getRingtoneUri(fileName: String): Uri? {
        if (TextUtils.isEmpty(fileName)) {
            return getDefaultRingtoneUri()
        }

        // If system_ringtone_default is explicitly requested, bypass resource check
        if (fileName.equals("system_ringtone_default", true)) {
            return getDefaultRingtoneUri(useSystemDefault = true)
        }

        try {
            val resId = context.resources.getIdentifier(fileName, "raw", context.packageName)
            if (resId != 0) {
                return Uri.parse("android.resource://${context.packageName}/$resId")
            }

            Log.w(tag, "named alert was not in raw resources")
            return null
        } catch (e: Exception) {
            Log.w(tag, "named alert could not be resolved", e)
            return null
        }
    }

    private fun getDefaultRingtoneUri(useSystemDefault: Boolean = false): Uri? {
        try {
            if (!useSystemDefault) {
                // First try to use ringtone_default resource if it exists
                val resId =
                    context.resources.getIdentifier("ringtone_default", "raw", context.packageName)
                if (resId != 0) {
                    return Uri.parse("android.resource://${context.packageName}/$resId")
                }
            }

            // Fall back to system default ringtone
            return RingtoneManager.getActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE
            )
        } catch (e: Exception) {
            // getActualDefaultRingtoneUri can throw an exception on some devices
            // for custom ringtones
            return getSafeSystemRingtoneUri()
        }
    }

    private fun getSafeSystemRingtoneUri(): Uri? {
        try {
            val defaultUri = RingtoneManager.getActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE
            )

            val rm = RingtoneManager(context)
            rm.setType(RingtoneManager.TYPE_RINGTONE)
            val cursor = rm.cursor
            if (defaultUri != null && cursor != null) {
                while (cursor.moveToNext()) {
                    val uri = rm.getRingtoneUri(cursor.position)
                    if (uri == defaultUri) {
                        cursor.close()
                        return defaultUri
                    }
                }
            }

            // Default isn't system-provided → fallback to first available
            if (cursor != null && cursor.moveToFirst()) {
                val fallback = rm.getRingtoneUri(cursor.position)
                cursor.close()
                return fallback
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private var ringingTimer: Timer? = null

    // This function is called when the incoming call full intent (CallkitIncomingActivity) is shown
    // It prevents the sound from stopping when the screen is turned off because of an auto lock
    fun keepRingingOnFullScreen() {
        keepRingingForFullScreenIntent = true
        ringingTimer?.cancel()
        ringingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    keepRingingForFullScreenIntent = false
                }
            }, 500)
        }
    }
}