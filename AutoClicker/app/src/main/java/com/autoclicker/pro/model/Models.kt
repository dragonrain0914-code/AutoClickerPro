package com.autoclicker.pro.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// ── 點擊模式 ──────────────────────────────────────────────
enum class ClickMode {
    SINGLE,   // 單點模式
    DUAL,     // 雙點 A→B 循環
    MULTI     // 多點循環
}

// ── 執行狀態 FSM ──────────────────────────────────────────
enum class RunState {
    STOPPED,
    RUNNING,
    PAUSED
}

// ── 點位資料 ──────────────────────────────────────────────
data class ClickPoint(
    val id: Int,
    var x: Float,
    var y: Float,
    var delayAfterMs: Long = 0L,   // 此點擊後額外延遲（多點模式用）
    var label: String = "Point${id}"
)

// ── 連點設定 ──────────────────────────────────────────────
data class ClickConfig(
    val mode: ClickMode = ClickMode.SINGLE,
    val intervalMs: Long = 10L,         // 主點擊間隔
    val dualIntervalMs: Long = 10L,     // 雙點模式 A→B 間隔
    val points: List<ClickPoint> = listOf(ClickPoint(0, 540f, 960f)),
    val gestureDurationMs: Long = 1L    // dispatchGesture 持續時間，越短越快
)

// ── 執行統計 ──────────────────────────────────────────────
data class ClickStats(
    val totalClicks: Long = 0L,
    val elapsedMs: Long = 0L,
    val actualCps: Float = 0f           // 實際 clicks/s
)

// ── Service → UI 狀態快照 ─────────────────────────────────
data class RuntimeSnapshot(
    val state: RunState = RunState.STOPPED,
    val config: ClickConfig = ClickConfig(),
    val stats: ClickStats = ClickStats()
)
