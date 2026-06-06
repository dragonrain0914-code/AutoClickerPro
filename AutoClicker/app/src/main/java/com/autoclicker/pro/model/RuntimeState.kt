package com.autoclicker.pro.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全域執行狀態中心（單例）
 * AccessibilityService 與 OverlayService 透過此共享狀態。
 * 使用 StateFlow 實現響應式更新，無鎖且 coroutine 友好。
 */
object RuntimeState {

    // ── 執行快照 ─────────────────────────────────────────
    private val _snapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = _snapshot.asStateFlow()

    // ── 目標點位（懸浮窗拖曳後即時更新） ─────────────────
    private val _config = MutableStateFlow(ClickConfig())
    val config: StateFlow<ClickConfig> = _config.asStateFlow()

    // ── FSM 狀態 ──────────────────────────────────────────
    private val _runState = MutableStateFlow(RunState.STOPPED)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    // ── 統計 ─────────────────────────────────────────────
    private val _stats = MutableStateFlow(ClickStats())
    val stats: StateFlow<ClickStats> = _stats.asStateFlow()

    // ── Watchdog 心跳（ns，由 clicker loop 更新） ─────────
    @Volatile var lastHeartbeatNs: Long = System.nanoTime()

    // ── API ───────────────────────────────────────────────

    fun updateConfig(config: ClickConfig) {
        _config.value = config
    }

    fun transitionTo(state: RunState) {
        _runState.value = state
        refreshSnapshot()
    }

    fun updateStats(stats: ClickStats) {
        _stats.value = stats
        refreshSnapshot()
    }

    fun resetStats() {
        _stats.value = ClickStats()
    }

    private fun refreshSnapshot() {
        _snapshot.value = RuntimeSnapshot(
            state = _runState.value,
            config = _config.value,
            stats = _stats.value
        )
    }

    fun isRunning() = _runState.value == RunState.RUNNING
    fun isPaused()  = _runState.value == RunState.PAUSED
    fun isStopped() = _runState.value == RunState.STOPPED
}
