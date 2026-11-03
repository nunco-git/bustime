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
        private const val MAX_MS = 60L * 60L * 1000L   // 호출 횟수 절약: 60분 후 자동 종료 (예약 안내는 끝나는 시각까지)

        /** 지금 도는 서비스가 예약 안내이면 그 예약 id (수동 안내이면 null) */
        @Volatile var runningSchedule: String? = null
        @Volatile var runningSig: Int = 0
        @Volatile var running: Boolean = false       // 서비스가 도는 중인지(수동·예약 모두)
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
    /** 버스 한 대(노선)마다 따로 정하는 안내 설정 */
    private data class BusCfg(val minutes: Int, val voice: Boolean, val vibrate: Boolean, val repeat: Int, val gapMs: Long)
    private var cfgs: Map<String, BusCfg> = emptyMap()
    private val counts = mutableMapOf<String, Int>()
    private val lastAt = mutableMapOf<String, Long>()
    private var startedAt = 0L
    private var endAtWall = 0L                      // 예약 안내가 끝나는 시각(없으면 0)

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (endAtWall > 0) {
                if (System.currentTimeMillis() >= endAtWall) { stopSelf(); return }    // 예약 시간이 끝남
            } else if (SystemClock.elapsedRealtime() - startedAt > MAX_MS) {
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
            runningSchedule?.let { Schedules.skipToday(this, it) }    // 사용자가 직접 끈 예약은 오늘은 다시 켜지 않음
            stopSelf()
            return START_NOT_STICKY
        }

        cityCode = intent.getStringExtra("cityCode") ?: ""
        nodeId = intent.getStringExtra("nodeId") ?: ""
        nodeNm = intent.getStringExtra("nodeNm") ?: ""
        val json = intent.getStringExtra("cfgJson")
        val map = mutableMapOf<String, BusCfg>()
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val no = o.optString("no").replace(Regex("\\s+"), "")
                    if (no.isEmpty()) continue
                    map[no] = BusCfg(
                        o.optInt("min", 5).coerceIn(1, 60),
                        o.optBoolean("voice", true),
                        o.optBoolean("vib", false),
                        o.optInt("rep", 1).coerceIn(1, 20),
                        o.optInt("gap", 60).coerceIn(10, 600) * 1000L
                    )
                }
            } catch (e: Exception) { }
        } else {   // 예전 방식(모든 버스에 같은 설정)
            val c = BusCfg(
                intent.getIntExtra("minutes", 5),
                true,
                intent.getBooleanExtra("vibrate", false),
                intent.getIntExtra("repeat", 1).coerceIn(1, 20),
                intent.getIntExtra("gapSec", 60).coerceIn(10, 600) * 1000L
            )
            (intent.getStringExtra("buses") ?: "").split(",")
                .map { it.replace(Regex("\\s+"), "") }
                .filter { it.isNotEmpty() }
                .forEach { map[it] = c }
        }
        cfgs = map
        running = true
        runningSchedule = intent.getStringExtra("schedId")
        runningSig = intent.getIntExtra("sig", 0)
        endAtWall = intent.getLongExtra("endAt", 0L)
        counts.clear()
        lastAt.clear()
        startedAt = SystemClock.elapsedRealtime()

        createChannel()
        val noti = buildNotification(
            "$nodeNm · " + cfgs.entries.joinToString(", ") { "${it.key}번(${it.value.minutes}분 전)" }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, noti)
        }

        if (tts == null) tts = TextToSpeech(this, this)

        wakeLock?.let { if (it.isHeld) it.release() }
        run {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val dur = if (endAtWall > 0) maxOf(endAtWall - System.currentTimeMillis(), 0L) + 60_000L else MAX_MS + 60_000L
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bustime:watch").apply { acquire(dur) }
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
        val now = SystemClock.elapsedRealtime()
        val seen = mutableSetOf<String>()
        for ((no, sec) in items) {
            val n = no.replace(Regex("\\s+"), "")
            val cfg = cfgs[n] ?: continue
            seen.add(n)
            val limit = cfg.minutes * 60
            if (sec <= limit) {
                val cnt = counts[n] ?: 0
                val last = lastAt[n] ?: 0L
                // 조회 주기(30초)에 약간의 오차가 있어 5초 여유를 둠
                if (cnt < cfg.repeat && (cnt == 0 || now - last >= cfg.gapMs - 5_000L)) {
                    counts[n] = cnt + 1
                    lastAt[n] = now
                    val m = Math.round(sec / 60.0).toInt()
                    val whenText = if (m <= 1) "곧 도착합니다" else "약 ${m}분 후 도착합니다"
                    if (cfg.vibrate) Buzz.vibrate(this)
                    if (cfg.voice) speak("${busLabel(no)}가 $nodeNm 정류장에 $whenText.")
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
        runningSchedule = null
        running = false
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        executor.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { try { if (it.isHeld) it.release() } catch (e: Exception) { } }
        super.onDestroy()
    }
}
