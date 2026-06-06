package com.autoclicker.pro.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.autoclicker.pro.R
import com.autoclicker.pro.accessibility.ClickAccessibilityService
import com.autoclicker.pro.model.*
import com.autoclicker.pro.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "OverlayService"
private const val CHANNEL_ID = "autoclicker_fg"
private const val NOTIF_ID = 1001

/**
 * 前景服務：管理懸浮控制面板 + 目標紅點
 *
 * 架構：
 * - LifecycleService 提供 lifecycleScope
 * - ControlPanelView：Start/Pause/Stop 控制面板（可拖曳）
 * - TargetDotView：紅色目標點（可拖曳，即時顯示座標）
 * - 訂閱 RuntimeState 更新 UI
 */
class OverlayService : LifecycleService() {

    companion object {
        @Volatile var instance: OverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private var controlPanel: View? = null
    private var targetDot: View? = null

    // 面板位置
    private var panelX = 0; private var panelY = 200
    private var dotX = 540f; private var dotY = 960f

    // ─────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        addControlPanel()
        addTargetDot()
        observeSnapshot()
        Log.i(TAG, "OverlayService started")
    }

    override fun onDestroy() {
        instance = null
        safeRemoveView(controlPanel)
        safeRemoveView(targetDot)
        super.onDestroy()
        Log.i(TAG, "OverlayService destroyed")
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ─────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────

    private fun addControlPanel() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_control_panel, null)
        val params = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            panelX, panelY
        )
        setupControlPanel(view)
        windowManager.addView(view, params)
        controlPanel = view
    }

    private fun addTargetDot() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_target_dot, null)
        val params = overlayParams(120, 120, dotX.toInt(), dotY.toInt())
        setupTargetDot(view, params)
        windowManager.addView(view, params)
        targetDot = view
        // 初始化點位
        updateSinglePoint(dotX, dotY)
    }

    private fun setupControlPanel(view: View) {
        // 拖曳
        makeDraggable(view, controlPanel) { x, y -> panelX = x; panelY = y }

        // 按鈕
        view.findViewById<View>(R.id.btnStart).setOnClickListener {
            if (ClickAccessibilityService.isActive()) {
                RuntimeState.updateConfig(RuntimeState.config.value)
                RuntimeState.transitionTo(RunState.RUNNING)
            } else {
                showAccessibilityToast()
            }
        }
        view.findViewById<View>(R.id.btnPause).setOnClickListener {
            when (RuntimeState.runState.value) {
                RunState.RUNNING -> RuntimeState.transitionTo(RunState.PAUSED)
                RunState.PAUSED  -> RuntimeState.transitionTo(RunState.RUNNING)
                else -> {}
            }
        }
        view.findViewById<View>(R.id.btnStop).setOnClickListener {
            RuntimeState.transitionTo(RunState.STOPPED)
        }
    }

    private fun setupTargetDot(view: View, params: WindowManager.LayoutParams) {
        makeDraggable(view, targetDot) { x, y ->
            dotX = x.toFloat(); dotY = y.toFloat()
            params.x = x; params.y = y
            windowManager.updateViewLayout(view, params)
            updateSinglePoint(dotX, dotY)
            // 更新座標顯示
            updateCoordDisplay(x.toFloat(), y.toFloat())
        }
    }

    private fun updateSinglePoint(x: Float, y: Float) {
        val current = RuntimeState.config.value
        val newPoints = current.points.toMutableList()
        if (newPoints.isEmpty()) {
            newPoints.add(ClickPoint(0, x, y))
        } else {
            newPoints[0] = newPoints[0].copy(x = x, y = y)
        }
        RuntimeState.updateConfig(current.copy(points = newPoints))
    }

    private fun updateCoordDisplay(x: Float, y: Float) {
        targetDot?.findViewById<android.widget.TextView>(R.id.tvCoords)?.text =
            "X:${x.toInt()} Y:${y.toInt()}"
    }

    // ─────────────────────────────────────────────────────
    // Snapshot observer → update panel UI
    // ─────────────────────────────────────────────────────

    private fun observeSnapshot() {
        lifecycleScope.launch {
            RuntimeState.snapshot.collectLatest { snapshot ->
                updatePanelUI(snapshot)
            }
        }
    }

    private fun updatePanelUI(snapshot: RuntimeSnapshot) {
        val view = controlPanel ?: return
        val tvState   = view.findViewById<android.widget.TextView>(R.id.tvState)
        val tvSpeed   = view.findViewById<android.widget.TextView>(R.id.tvSpeed)
        val tvClicks  = view.findViewById<android.widget.TextView>(R.id.tvClicks)
        val btnPause  = view.findViewById<android.widget.Button>(R.id.btnPause)

        tvState.text = when (snapshot.state) {
            RunState.RUNNING -> "● RUNNING"
            RunState.PAUSED  -> "⏸ PAUSED"
            RunState.STOPPED -> "■ STOPPED"
        }
        tvState.setTextColor(when (snapshot.state) {
            RunState.RUNNING -> 0xFF00FF44.toInt()
            RunState.PAUSED  -> 0xFFFFAA00.toInt()
            RunState.STOPPED -> 0xFF888888.toInt()
        })

        tvSpeed.text  = "${snapshot.config.intervalMs}ms"
        tvClicks.text = formatCount(snapshot.stats.totalClicks)
        btnPause.text = if (snapshot.state == RunState.PAUSED) "Resume" else "Pause"
    }

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun makeDraggable(view: View, ref: View?, onMove: (Int, Int) -> Unit) {
        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var isDrag = false

        view.setOnTouchListener { v, event ->
            val params = (ref ?: view).layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDrag = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!isDrag && (Math.abs(dx) > 5 || Math.abs(dy) > 5)) isDrag = true
                    if (isDrag) {
                        params.x = initX + dx; params.y = initY + dy
                        windowManager.updateViewLayout(ref ?: view, params)
                        onMove(params.x, params.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    !isDrag // 若非拖曳則傳遞點擊事件
                }
                else -> false
            }
        }
    }

    private fun safeRemoveView(view: View?) {
        try { view?.let { windowManager.removeView(it) } } catch (_: Exception) {}
    }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun formatCount(n: Long): String {
        return when {
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000     -> "%.1fK".format(n / 1_000.0)
            else           -> n.toString()
        }
    }

    private fun showAccessibilityToast() {
        android.widget.Toast.makeText(
            this, "請先開啟無障礙服務", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ─────────────────────────────────────────────────────
    // Foreground Notification
    // ─────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "AutoClicker", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "連點器執行中" }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker 執行中")
            .setContentText("點擊以開啟控制介面")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
