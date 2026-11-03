package com.bustime.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var testTts: TextToSpeech? = null
    private var testReady = false

    private val siteUrl = "https://bustime-c4cfd.web.app"

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
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl(siteUrl)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
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
