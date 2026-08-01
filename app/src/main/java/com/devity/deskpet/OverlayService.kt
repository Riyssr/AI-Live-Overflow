package com.devity.deskpet

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import io.supabase.SupabaseClient
import io.supabase.createSupabaseClient
import io.supabase.postgrest.Postgrest
import kotlinx.coroutines.*

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var supabase: SupabaseClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollingJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "devity_pet"
        private const val NOTIFICATION_ID = 1001
        private const val PET_WIDTH_DP = 180
        private const val PET_HEIGHT_DP = 240

        // Supabase 配置 — 构建时替换
        const val SUPABASE_URL = "https://wdzdbyxamlufrjjlhubw.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_O2g0ln3CzT702uIBRfLB7w_smK9vIod"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initSupabase()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Devity 在你身边 👾"))
        setupOverlay()
        startPolling()
    }

    private fun initSupabase() {
        supabase = createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
            install(Postgrest)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_WIDTH_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === 手势处理 ===

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> sendGesture("long_press")
                            System.currentTimeMillis() - lastTapTime < 300 -> sendGesture("double_tap")
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                sendGesture("tap")
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun sendGesture(type: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onGesture('$type')", null
        )
        // 异步上报到 Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                supabase.postgrest["gesture_logs"].insert(mapOf(
                    "type" to type,
                    "timestamp" to System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
        }
    }

    // === Supabase 状态轮询 ===

    private fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000)
                try {
                    val result = supabase.postgrest["pet_state"]
                        .select()
                        .order("id", Postgrest.Order.DESCENDING)
                        .limit(1)
                    val state = result.decodeAs<List<PetState>>()
                    if (state.isNotEmpty()) {
                        mainHandler.post {
                            applyState(state[0])
                        }
                    }
                } catch (_: Exception) {
                    // 静默失败，下次重试
                }
            }
        }
    }

    private fun applyState(state: PetState) {
        val js = buildString {
            append("window.petEngine && window.petEngine.setState({")
            state.expression?.let { append("expression:'$it',") }
            state.bubble?.let { append("bubble:'$it',") }
            state.heat?.let { append("heat:$it,") }
            state.animation?.let { append("animation:'$it'") }
            append("})")
        }
        overlayView?.evaluateJavascript(js, null)
    }

    // === 通知 ===

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Devity")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Devity桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "桌宠保活通知"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // === 工具 ===

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}

data class PetState(
    val expression: String? = null,
    val bubble: String? = null,
    val heat: Int? = null,
    val animation: String? = null
)
