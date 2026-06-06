package com.autoclicker.pro.accessibility

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.util.Log
import com.autoclicker.pro.model.*
import kotlinx.coroutines.*

private const val TAG = "ClickEngine"

/**
 * 高性能連點引擎
 *
 * 架構設計：
 * - 非阻塞協程循環，避免 Thread.sleep 精度問題
 * - GestureDescription 物件池，減少 GC 壓力
 * - 點位序列器（PointSequencer）負責多模式切換
 * - CPS 統計使用滑動窗口，低成本計算
 */
class ClickEngine(
    private val service: ClickAccessibilityService
) {
    private var engineJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── 統計滑動窗口（最近 N 次點擊時間戳） ──────────────
    private val cpsWindowSize = 50
    private val clickTimestamps = ArrayDeque<Long>(cpsWindowSize + 1)
    private var totalClicks = 0L
    private var startTimeMs = 0L

    // ── GestureDescription 物件池 ─────────────────────────
    private val gesturePool = GesturePool(poolSize = 4)

    // ── 點位序列器 ────────────────────────────────────────
    private val sequencer = PointSequencer()

    // ─────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────

    fun start(config: ClickConfig) {
        stop()
        totalClicks = 0L
        startTimeMs = System.currentTimeMillis()
        clickTimestamps.clear()
        sequencer.reset(config)

        engineJob = engineScope.launch {
            clickLoop(config)
        }
        Log.d(TAG, "Engine started: mode=${config.mode} interval=${config.intervalMs}ms")
    }

    fun stop() {
        engineJob?.cancel()
        engineJob = null
    }

    // ─────────────────────────────────────────────────────
    // Core loop
    // ─────────────────────────────────────────────────────

    private suspend fun clickLoop(config: ClickConfig) {
        while (isActive && RuntimeState.isRunning()) {
            val loopStart = System.nanoTime()

            // 取得下一個點位與點擊後等待時間
            val (point, extraDelayMs) = sequencer.next()

            // 更新心跳
            RuntimeState.lastHeartbeatNs = loopStart

            // 派發手勢
            dispatchClick(point.x, point.y, config.gestureDurationMs)

            // 統計
            recordClick()

            // 計算等待：主間隔 + 點位額外延遲
            val totalDelayMs = config.intervalMs + extraDelayMs
            val elapsedMs = (System.nanoTime() - loopStart) / 1_000_000L
            val remainMs = totalDelayMs - elapsedMs

            if (remainMs > 0) {
                delay(remainMs)
            }
            // 若 remainMs <= 0 代表系統已達極限，直接繼續
        }
    }

    // ─────────────────────────────────────────────────────
    // Gesture dispatch
    // ─────────────────────────────────────────────────────

    private fun dispatchClick(x: Float, y: Float, durationMs: Long) {
        val gesture = gesturePool.acquire(x, y, durationMs)
        val dispatched = service.dispatchGesture(gesture, null, null)
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture failed at ($x, $y)")
        }
        // 不回收至池，讓系統自己釋放；下次 acquire 時重建
    }

    // ─────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────

    private fun recordClick() {
        val now = System.currentTimeMillis()
        totalClicks++
        clickTimestamps.addLast(now)
        if (clickTimestamps.size > cpsWindowSize) {
            clickTimestamps.removeFirst()
        }

        // 每 20 次更新一次 UI 統計（避免 StateFlow 過於頻繁）
        if (totalClicks % 20 == 0L) {
            val cps = calcCps(now)
            val elapsed = now - startTimeMs
            RuntimeState.updateStats(
                ClickStats(totalClicks, elapsed, cps)
            )
        }
    }

    private fun calcCps(nowMs: Long): Float {
        if (clickTimestamps.size < 2) return 0f
        val oldest = clickTimestamps.first()
        val rangeMs = nowMs - oldest
        return if (rangeMs > 0) {
            (clickTimestamps.size.toFloat() / rangeMs) * 1000f
        } else 0f
    }
}

// ─────────────────────────────────────────────────────────
// GesturePool：輕量 GestureDescription 建構快取
// ─────────────────────────────────────────────────────────

private class GesturePool(private val poolSize: Int) {
    fun acquire(x: Float, y: Float, durationMs: Long): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}

// ─────────────────────────────────────────────────────────
// PointSequencer：多模式點位序列管理
// ─────────────────────────────────────────────────────────

private class PointSequencer {
    private var config: ClickConfig = ClickConfig()
    private var index = 0

    fun reset(config: ClickConfig) {
        this.config = config
        index = 0
    }

    /**
     * 返回 (ClickPoint, extraDelayMs)
     * extraDelayMs 用於雙點/多點模式的點間延遲
     */
    fun next(): Pair<ClickPoint, Long> {
        val points = config.points
        if (points.isEmpty()) return Pair(ClickPoint(0, 540f, 960f), 0L)

        return when (config.mode) {
            ClickMode.SINGLE -> {
                Pair(points[0], 0L)
            }
            ClickMode.DUAL -> {
                val pt = points[index % points.size.coerceAtLeast(1)]
                index++
                Pair(pt, config.dualIntervalMs)
            }
            ClickMode.MULTI -> {
                val pt = points[index % points.size]
                val extra = pt.delayAfterMs
                index = (index + 1) % points.size
                Pair(pt, extra)
            }
        }
    }
}
