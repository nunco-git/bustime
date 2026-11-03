package com.bustime.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var testTts: TextToSpeech? = null
    private var testReady = false
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private val siteUrl = "https://bustime-c4cfd.web.app"
    // 안드로이드 7.0 미만(구형 폴더폰 등)은 가벼운 간단 화면을 먼저 보여 줌
    private val startUrl get() = if (Build.VERSION.SDK_INT < 24) "$siteUrl/lite.html" else siteUrl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        val root = FrameLayout(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        // 상태바/내비게이션바에 화면이 가려지지 않게 여백 적용
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // 즐겨찾기/설정 저장(localStorage)
        web.webViewClient = WebViewClient()
        // 지도의 '내 위치' 버튼: 위치 권한 요청을 웹 화면에 연결
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = Pair(origin, callback)
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2
                    )
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl(startUrl)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            pendingGeo?.let { it.second.invoke(it.first, grantResults.any { r -> r == PackageManager.PERMISSION_GRANTED }, false) }
            pendingGeo = null
        }
    }

    /** 웹 화면(자바스크립트)에서 부르는 기능 */
    inner class Bridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun startWatch(cityCode: String, nodeId: String, nodeNm: String, buses: String, minutes: Int) {
            val i = Intent(this@MainActivity, WatchService::class.java)
                .setAction(WatchService.ACTION_START)
                .putExtra("cityCode", cityCode)
                .putExtra("nodeId", nodeId)
                .putExtra("nodeNm", nodeNm)
                .putExtra("buses", buses)
                .putExtra("minutes", minutes)
            ContextCompat.startForegroundService(this@MainActivity, i)
        }

        /** 진동/반복 안내 설정을 함께 받는 새 버전 */
        @JavascriptInterface
        fun startWatch2(
            cityCode: String, nodeId: String, nodeNm: String, buses: String, minutes: Int,
            vibrate: Boolean, repeat: Int, gapSec: Int
        ) {
            val i = Intent(this@MainActivity, WatchService::class.java)
                .setAction(WatchService.ACTION_START)
                .putExtra("cityCode", cityCode)
                .putExtra("nodeId", nodeId)
                .putExtra("nodeNm", nodeNm)
                .putExtra("buses", buses)
                .putExtra("minutes", minutes)
                .putExtra("vibrate", vibrate)
                .putExtra("repeat", repeat)
                .putExtra("gapSec", gapSec)
            ContextCompat.startForegroundService(this@MainActivity, i)
        }

        /** 배터리 사용 제한이 풀려 있는지 (제한이 있으면 화면이 꺼졌을 때 안내가 늦거나 멈출 수 있음) */
        @JavascriptInterface
        fun batteryOk(): Boolean {
            if (Build.VERSION.SDK_INT < 23) return true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }

        /** "배터리 사용량 제한 없음" 허용 창을 띄움 */
        @JavascriptInterface
        fun requestBattery() {
            if (Build.VERSION.SDK_INT < 23) return
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun vibrateTest() {
            Buzz.vibrate(this@MainActivity)
        }

        @JavascriptInterface
        fun stopWatch() {
            stopService(Intent(this@MainActivity, WatchService::class.java))
        }

        @JavascriptInterface
        fun speakTest() {
            runOnUiThread { doSpeakTest() }
        }
    }

    private fun doSpeakTest() {
        val msg = "음성 안내 테스트입니다. 이 소리가 들리면 정상입니다."
        if (testTts == null) {
            testTts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = testTts?.setLanguage(Locale.KOREAN)
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Toast.makeText(
                            this,
                            "한국어 음성이 없습니다. 폰 설정의 '텍스트 음성 변환'에서 한국어를 설치하세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        testTts?.setSpeechRate(0.9f)
                        testReady = true
                        testTts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "test")
                    }
                } else {
                    Toast.makeText(this, "이 폰에서는 음성 안내를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
                }
            }
        } else if (testReady) {
            testTts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    override fun onDestroy() {
        testTts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
