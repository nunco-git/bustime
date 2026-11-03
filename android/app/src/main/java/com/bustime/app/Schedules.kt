package com.bustime.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 예약 안내: 정해진 요일·시간에 폰의 알람 기능이 앱을 깨워 안내 서비스를 자동으로 켠다.
 * 예약 목록은 웹 화면(계정)에서 받아 이 기기에 저장해 두므로, 앱이 꺼져 있어도 시간이 되면 실행된다.
 */
object Schedules {
    const val ACTION_FIRE = "com.bustime.app.SCHED_FIRE"
    private const val PREF = "sched"

    /** days: 1=월 … 7=일, aMin/bMin: 하루 중 시작/끝 시각(분) */
    class S(val id: String, val days: Set<Int>, val aMin: Int, val bMin: Int, val raw: JSONObject)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(ctx: Context, json: String) {
        prefs(ctx).edit().putString("json", json).apply()
    }

    fun load(ctx: Context): List<S> {
        val out = mutableListOf<S>()
        try {
            val arr = JSONArray(prefs(ctx).getString("json", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optBoolean("on", true)) continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val ds = mutableSetOf<Int>()
                val da = o.optJSONArray("d")
                if (da != null) for (k in 0 until da.length()) ds.add(da.optInt(k))
                val a = toMin(o.optString("a")) ?: continue
                val b = toMin(o.optString("b")) ?: continue
                if (ds.isEmpty() || b <= a) continue
                out.add(S(id, ds, a, b, o))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun toMin(t: String): Int? {
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun weekday(cal: Calendar): Int = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1   // 월=1 … 일=7

    private fun dateKey(cal: Calendar) = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"

    private fun inWindow(s: S, now: Calendar): Boolean {
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return weekday(now) in s.days && m >= s.aMin && m < s.bMin
    }

    private fun endAtMs(s: S, now: Calendar): Long {
        val c = now.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, s.bMin / 60); c.set(Calendar.MINUTE, s.bMin % 60)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun nextStart(s: S, nowMs: Long): Long? {
        for (off in 0..7) {
            val c = Calendar.getInstance()
            c.timeInMillis = nowMs
            c.add(Calendar.DAY_OF_YEAR, off)
            c.set(Calendar.HOUR_OF_DAY, s.aMin / 60); c.set(Calendar.MINUTE, s.aMin % 60)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (weekday(c) in s.days && c.timeInMillis > nowMs) return c.timeInMillis
        }
        return null
    }

    fun skipToday(ctx: Context, id: String) {
        prefs(ctx).edit().putString("skip_$id", dateKey(Calendar.getInstance())).apply()
    }

    private fun skipped(ctx: Context, id: String) =
        prefs(ctx).getString("skip_$id", "") == dateKey(Calendar.getInstance())

    private fun pending(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun exactOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    /** 알람을 다시 걸고, startNow가 true이면 지금 예약 시간 안인 예약은 바로 시작한다. */
    fun arm(ctx: Context, startNow: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = prefs(ctx)
        // 이전에 걸어 둔 알람 모두 취소
        for (id in (p.getString("armed", "") ?: "").split(",").filter { it.isNotEmpty() }) {
            try { am.cancel(pending(ctx, id)) } catch (e: Exception) { }
        }
        val list = load(ctx)
        p.edit().putString("armed", list.joinToString(",") { it.id }).apply()
        val now = System.currentTimeMillis()
        for (s in list) {
            val t = nextStart(s, now) ?: continue
            try {
                if (exactOk(ctx) && Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(ctx, s.id))
                else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(ctx, s.id))
                else am.set(AlarmManager.RTC_WAKEUP, t, pending(ctx, s.id))
            } catch (e: Exception) { }
        }
        // 예약이 사라졌거나 꺼졌는데 그 예약으로 돌고 있던 서비스는 멈춤
        val running = WatchService.runningSchedule
        if (running != null && list.none { it.id == running }) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }
        if (startNow) {
            val cal = Calendar.getInstance()
            for (s in list) if (inWindow(s, cal) && !skipped(ctx, s.id)) start(ctx, s, endAtMs(s, cal), false)
        }
    }

    /** 알람 시각이 되었을 때 */
    fun fire(ctx: Context, id: String) {
        val cal = Calendar.getInstance()
        val s = load(ctx).firstOrNull { it.id == id }
        if (s != null && inWindow(s, cal) && !skipped(ctx, id)) start(ctx, s, endAtMs(s, cal), true)
        arm(ctx, false)      // 다음 예약 시각을 다시 걸어 둠
    }

    /** force: 예약 시각이 되어 시작할 때는 수동 안내를 대체하고, 앱 화면 동기화 때는 수동 안내를 건드리지 않음 */
    private fun start(ctx: Context, s: S, endAt: Long, force: Boolean) {
        if (!force && WatchService.running && WatchService.runningSchedule == null) return
        val sig = s.raw.toString().hashCode()
        if (WatchService.runningSchedule == s.id && WatchService.runningSig == sig) return   // 이미 같은 설정으로 실행 중
        val stop = s.raw.optJSONObject("stop") ?: return
        val arr = JSONArray()
        for (no in s.raw.optString("buses").split(",").map { it.replace(Regex("\\s+"), "") }.filter { it.isNotEmpty() }) {
            arr.put(JSONObject()
                .put("no", no)
                .put("min", s.raw.optInt("m", 5))
                .put("voice", s.raw.optBoolean("v", true))
                .put("vib", s.raw.optBoolean("z", true))
                .put("rep", s.raw.optInt("r", 1))
                .put("gap", s.raw.optInt("g", 60)))
        }
        if (arr.length() == 0) return
        val i = Intent(ctx, WatchService::class.java)
            .setAction(WatchService.ACTION_START)
            .putExtra("cityCode", stop.optString("cityCode"))
            .putExtra("nodeId", stop.optString("nodeid"))
            .putExtra("nodeNm", stop.optString("nodenm"))
            .putExtra("cfgJson", arr.toString())
            .putExtra("schedId", s.id)
            .putExtra("sig", sig)
            .putExtra("endAt", endAt)
        try { ContextCompat.startForegroundService(ctx, i) } catch (e: Exception) { }
    }
}
