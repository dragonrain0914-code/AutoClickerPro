package com.autoclicker.pro.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.pro.model.*
import com.autoclicker.pro.overlay.OverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

private const val TAG = "ClickAccessibility"

/**
 * 無障礙服務主體
 *
 * 責任：
 * 1. 持有 ClickEngine（唯一 dispatchGesture 呼叫點）
 * 2. 響應 RuntimeState FSM 狀態轉換
 * 3. 管理 Watchdog
 * 4. 服務重啟後自動恢復狀態
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        fun isActive() = instance != null
    }

    private lateinit var engine: ClickEngine
    private lateinit var watchdog: EngineWatchdog

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine = ClickEngine(this)
        watchdog = EngineWatchdog(this)

        Log.i(TAG, "AccessibilityService connected")

        // 訂閱 FSM 狀態，響應 start/pause/resume/stop
        observeRunState()

        // 通知 OverlayService 服務已就緒
        sendBroadcast(Intent(ACTION_SERVICE_CONNECTED).apply {
            setPackage(packageName)
        })
    }

    override fun onDestroy() {
        instance = null
        engine.stop()
        watchdog.stop()
        serviceScope.cancel()
        // 若仍在執行中，標記為停止
        if (!RuntimeState.isStopped()) {
            RuntimeState.transitionTo(RunState.STOPPED)
        }
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要監聽任何事件，留空即可（不耗費 CPU）
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // ─────────────────────────────────────────────────────
    // FSM Observer
    // ─────────────────────────────────────────────────────

    private fun observeRunState() {
        serviceScope.launch {
            RuntimeState.runState.collectLatest { state ->
                handleStateTransition(state)
            }
        }
    }

    private fun handleStateTransition(state: RunState) {
        val config = RuntimeState.config.value
        when (state) {
            RunState.RUNNING -> {
                engine.start(config)
                watchdog.start()
                Log.i(TAG, "Engine RUNNING")
            }
            RunState.PAUSED -> {
                engine.stop()
                // watchdog 繼續監控，但心跳已停，不觸發重啟
                watchdog.stop()
                Log.i(TAG, "Engine PAUSED")
            }
            RunState.STOPPED -> {
                engine.stop()
                watchdog.stop()
                RuntimeState.resetStats()
                Log.i(TAG, "Engine STOPPED")
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // Internal: Watchdog 觸發重啟
    // ─────────────────────────────────────────────────────

    internal fun restartEngine(config: ClickConfig) {
        Log.w(TAG, "Restarting engine by watchdog")
        engine.stop()
        engine.start(config)
    }

    // ─────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────

    companion object {
        const val ACTION_SERVICE_CONNECTED = "com.autoclicker.pro.SERVICE_CONNECTED"
        const val ACTION_SERVICE_DISCONNECTED = "com.autoclicker.pro.SERVICE_DISCONNECTED"
    }
}
