package com.bustime.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 예약 시각 알람, 폰 재부팅, 앱 업데이트 때 예약 안내를 다시 걸거나 시작한다. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Schedules.ACTION_FIRE -> Schedules.fire(context, intent.getStringExtra("id") ?: return)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Schedules.arm(context, true)
        }
    }
}
