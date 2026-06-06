package com.autoclicker.pro.accessibility

import android.util.Log
import com.autoclicker.pro.model.ClickConfig
import com.autoclicker.pro.model.RuntimeState
import com.autoclicker.pro.model.RunState
import kotlinx.coroutines.*

private const val TAG = "Watchdog"
private const val HEARTBEAT_TIMEOUT_MS = 5_000L   // 5 秒無心跳視為卡死
private const val CHECK_INTERVAL_MS    = 2_000L   // 每 2 秒檢查一次

/**
 * Watchdog 系統
 *
 * 監控 ClickEngine 心跳，若引擎卡死則自動重啟。
 * 設計為獨立協程，不阻塞主引擎。
 */
class EngineWatchdog(
    private val service: ClickAccessibilityService
) {
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var restartCount = 0

    fun start() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            Log.d(TAG, "Watchdog started")
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkHeartbeat()
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        restartCount = 0
        Log.d(TAG, "Watchdog stopped")
    }

    private fun checkHeartbeat() {
        if (!RuntimeState.isRunning()) return

        val nowNs = System.nanoTime()
        val lastBeat = RuntimeState.lastHeartbeatNs
        val elapsedMs = (nowNs - lastBeat) / 1_000_000L

        if (elapsedMs > HEARTBEAT_TIMEOUT_MS) {
            restartCount++
            Log.w(TAG, "Heartbeat timeout! Elapsed=${elapsedMs}ms, restart#$restartCount")
            // 重啟引擎
            val config = RuntimeState.config.value
            service.restartEngine(config)
            // 更新心跳避免重複觸發
            RuntimeState.lastHeartbeatNs = System.nanoTime()
        }
    }
}
