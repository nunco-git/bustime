package com.bustime.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 화면이 꺼져 있어도 정류장 도착정보를 주기적으로 확인하고,
 * 지정한 버스가 정해진 시간 안에 들어오면 음성으로 안내하는 서비스.
 */
class WatchService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.bustime.app.START"
        const val ACTION_STOP = "com.bustime.app.STOP"
        private const val CHANNEL_ID = "bus_watch"
        private const val NOTI_ID = 1
        private const val BASE = "https://bustime-c4cfd.web.app/api"
        private const val POLL_MS = 30_000L            // 30초마다 확인
        private const val MAX_MS = 60L * 60L * 1000L   // 호출 횟수 절약: 60분 후 자동 종료
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = mutableListOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var cityCode = ""
    private var nodeId = ""
    private var nodeNm = ""
    private var wanted: Set<String> = emptySet()
    private var minutes = 5
    private var vibrate = false
    private var repeatTotal = 1                     // 한 버스당 안내 횟수
    private var gapMs = 60_000L                     // 반복 간격
    private val counts = mutableMapOf<String, Int>()
    private val lastAt = mutableMapOf<String, Long>()
    private var startedAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - startedAt > MAX_MS) {
                speak("안내 시간이 지나 음성 안내를 종료합니다.")
                handler.postDelayed({ stopSelf() }, 6000)
                return
            }
            executor.execute { poll() }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        cityCode = intent.getStringExtra("cityCode") ?: ""
        nodeId = intent.getStringExtra("nodeId") ?: ""
        nodeNm = intent.getStringExtra("nodeNm") ?: ""
        val buses = intent.getStringExtra("buses") ?: ""
        minutes = intent.getIntExtra("minutes", 5)
        wanted = buses.split(",")
            .map { it.replace(Regex("\\s+"), "") }
            .filter { it.isNotEmpty() }
            .toSet()
        vibrate = intent.getBooleanExtra("vibrate", false)
        repeatTotal = intent.getIntExtra("repeat", 1).coerceIn(1, 20)
        gapMs = intent.getIntExtra("gapSec", 60).coerceIn(10, 600) * 1000L
        counts.clear()
        lastAt.clear()
        startedAt = SystemClock.elapsedRealtime()

        createChannel()
        val noti = buildNotification(
            "$nodeNm · $buses · ${minutes}분 전 안내" + (if (repeatTotal > 1) " · ${repeatTotal}회 반복" else "") + (if (vibrate) " · 진동" else "")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, noti)
        }

        if (tts == null) tts = TextToSpeech(this, this)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bustime:watch").apply {
                acquire(MAX_MS + 60_000L)
            }
        }

        // 화면이 꺼져도 와이파이가 잠들지 않게 함 (꺼지면 도착정보 조회가 막힘)
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bustime:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) { }
        }

        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            showNoti("이 폰에서는 음성 안내를 사용할 수 없습니다.")
            return
        }
        val r = tts?.setLanguage(Locale.KOREAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            showNoti("한국어 음성이 없습니다. 폰 설정의 '텍스트 음성 변환'에서 한국어를 설치하세요.")
            return
        }
        tts?.setSpeechRate(0.9f)
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ttsReady = true
        for (t in pending) tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "bus")
        pending.clear()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "bus") else pending.add(text)
    }

    /** 백그라운드 스레드: 도착정보 조회 */
    private fun poll() {
        val items = fetchArrivals() ?: return
        handler.post { evaluate(items) }
    }

    private fun fetchArrivals(): List<Pair<String, Int>>? {
        val u = "$BASE?op=arrivals&cityCode=${Uri.encode(cityCode)}&nodeId=${Uri.encode(nodeId)}" +
            "&t=${System.currentTimeMillis()}"
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val body = JSONObject(text).optJSONObject("response")?.optJSONObject("body")
                ?: return null
            val items = body.opt("items")
            val out = mutableListOf<Pair<String, Int>>()
            if (items is JSONObject) {
                val item = items.opt("item")
                if (item is JSONArray) {
                    for (i in 0 until item.length()) {
                        val o = item.optJSONObject(i) ?: continue
                        out.add(Pair(o.optString("routeno"), o.optInt("arrtime", Int.MAX_VALUE)))
                    }
                } else if (item is JSONObject) {
                    out.add(Pair(item.optString("routeno"), item.optInt("arrtime", Int.MAX_VALUE)))
                }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** 메인 스레드: 안내 조건 확인 (반복 안내 포함) */
    private fun evaluate(items: List<Pair<String, Int>>) {
        val limit = minutes * 60
        val now = SystemClock.elapsedRealtime()
        val seen = mutableSetOf<String>()
        for ((no, sec) in items) {
            val n = no.replace(Regex("\\s+"), "")
            if (!wanted.contains(n)) continue
            seen.add(n)
            if (sec <= limit) {
                val cnt = counts[n] ?: 0
                val last = lastAt[n] ?: 0L
                // 조회 주기(30초)에 약간의 오차가 있어 5초 여유를 둠
                if (cnt < repeatTotal && (cnt == 0 || now - last >= gapMs - 5_000L)) {
                    counts[n] = cnt + 1
                    lastAt[n] = now
                    val m = Math.round(sec / 60.0).toInt()
                    val whenText = if (m <= 1) "곧 도착합니다" else "약 ${m}분 후 도착합니다"
                    if (vibrate) Buzz.vibrate(this)
                    speak("${busLabel(no)}가 $nodeNm 정류장에 $whenText.")
                }
            } else if (sec > limit + 120) {
                counts.remove(n)      // 다음 버스를 위해 초기화
                lastAt.remove(n)
            }
        }
        counts.keys.retainAll(seen)   // 목록에서 사라진(지나간) 버스는 초기화
        lastAt.keys.retainAll(seen)
    }

    private fun busLabel(no: String): String =
        if (Regex("^\\d+(-\\d+)?$").matches(no.trim())) "${no}번 버스" else "$no 버스"

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "버스 안내", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("버스 도착 음성 안내 중")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "안내 종료", stop)
            .build()
    }

    private fun showNoti(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_ID, buildNotification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        executor.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { try { if (it.isHeld) it.release() } catch (e: Exception) { } }
        super.onDestroy()
    }
}
