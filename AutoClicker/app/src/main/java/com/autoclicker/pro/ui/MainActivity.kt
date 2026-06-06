package com.autoclicker.pro.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autoclicker.pro.R
import com.autoclicker.pro.accessibility.ClickAccessibilityService
import com.autoclicker.pro.model.*
import com.autoclicker.pro.overlay.OverlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────
    private lateinit var tvPermOverlay: TextView
    private lateinit var tvPermAccessibility: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var cardSettings: View
    private lateinit var spinnerMode: Spinner
    private lateinit var spinnerInterval: Spinner
    private lateinit var etCustomInterval: EditText
    private lateinit var etDualInterval: EditText
    private lateinit var layoutDual: View
    private lateinit var layoutMulti: View
    private lateinit var llMultiPoints: LinearLayout
    private lateinit var btnAddPoint: Button
    private lateinit var btnStartOverlay: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvClickCount: TextView
    private lateinit var tvCps: TextView

    // ── 狀態 ─────────────────────────────────────────────
    private val multiPoints = mutableListOf<ClickPoint>()
    private var nextPointId = 1

    private val serviceConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshPermissionsUI()
        }
    }

    // ─────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupIntervalSpinner()
        setupModeSpinner()
        setupButtons()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsUI()
        registerReceiver(
            serviceConnectedReceiver,
            IntentFilter(ClickAccessibilityService.ACTION_SERVICE_CONNECTED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceConnectedReceiver) } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────
    // Bind Views
    // ─────────────────────────────────────────────────────

    private fun bindViews() {
        tvPermOverlay        = findViewById(R.id.tvPermOverlay)
        tvPermAccessibility  = findViewById(R.id.tvPermAccessibility)
        btnGrantOverlay      = findViewById(R.id.btnGrantOverlay)
        btnGrantAccessibility= findViewById(R.id.btnGrantAccessibility)
        cardSettings         = findViewById(R.id.cardSettings)
        spinnerMode          = findViewById(R.id.spinnerMode)
        spinnerInterval      = findViewById(R.id.spinnerInterval)
        etCustomInterval     = findViewById(R.id.etCustomInterval)
        etDualInterval       = findViewById(R.id.etDualInterval)
        layoutDual           = findViewById(R.id.layoutDual)
        layoutMulti          = findViewById(R.id.layoutMulti)
        llMultiPoints        = findViewById(R.id.llMultiPoints)
        btnAddPoint          = findViewById(R.id.btnAddPoint)
        btnStartOverlay      = findViewById(R.id.btnStartOverlay)
        tvStatus             = findViewById(R.id.tvStatus)
        tvClickCount         = findViewById(R.id.tvClickCount)
        tvCps                = findViewById(R.id.tvCps)
    }

    // ─────────────────────────────────────────────────────
    // Spinners
    // ─────────────────────────────────────────────────────

    private val intervalOptions = listOf("1ms", "2ms", "5ms", "10ms", "20ms", "自訂")
    private val intervalValues  = listOf(1L, 2L, 5L, 10L, 20L, -1L)

    private fun setupIntervalSpinner() {
        spinnerInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervalOptions
        )
        spinnerInterval.setSelection(3) // 預設 10ms
        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                etCustomInterval.visibility =
                    if (intervalValues[pos] == -1L) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private val modeLabels = listOf("單點模式", "雙點模式 (A→B)", "多點模式")

    private fun setupModeSpinner() {
        spinnerMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modeLabels
        )
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                layoutDual.visibility  = if (pos == 1) View.VISIBLE else View.GONE
                layoutMulti.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ─────────────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────────────

    private fun setupButtons() {
        btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnAddPoint.setOnClickListener { addMultiPoint() }

        btnStartOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "請先授予懸浮窗權限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyConfigAndStart()
        }

        // 快速控制
        findViewById<Button>(R.id.btnQuickStop).setOnClickListener {
            RuntimeState.transitionTo(RunState.STOPPED)
        }
    }

    private fun applyConfigAndStart() {
        val intervalMs = getSelectedInterval()
        if (intervalMs <= 0) {
            Toast.makeText(this, "請輸入有效的間隔時間", Toast.LENGTH_SHORT).show()
            return
        }
        val dualMs = etDualInterval.text.toString().toLongOrNull() ?: 10L
        val mode = when (spinnerMode.selectedItemPosition) {
            1    -> ClickMode.DUAL
            2    -> ClickMode.MULTI
            else -> ClickMode.SINGLE
        }
        val points = buildPoints(mode)
        val config = ClickConfig(
            mode           = mode,
            intervalMs     = intervalMs,
            dualIntervalMs = dualMs,
            points         = points,
            gestureDurationMs = 1L
        )
        RuntimeState.updateConfig(config)

        // 啟動懸浮窗服務
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "懸浮窗已開啟，請從懸浮面板控制", Toast.LENGTH_SHORT).show()
    }

    private fun getSelectedInterval(): Long {
        val pos = spinnerInterval.selectedItemPosition
        return if (intervalValues[pos] == -1L) {
            etCustomInterval.text.toString().toLongOrNull() ?: 0L
        } else {
            intervalValues[pos]
        }
    }

    private fun buildPoints(mode: ClickMode): List<ClickPoint> {
        return when (mode) {
            ClickMode.SINGLE -> listOf(ClickPoint(0, 540f, 960f))
            ClickMode.DUAL   -> listOf(
                ClickPoint(0, 400f, 800f, label = "A"),
                ClickPoint(1, 700f, 800f, label = "B")
            )
            ClickMode.MULTI  -> {
                if (multiPoints.isEmpty()) {
                    listOf(ClickPoint(0, 540f, 960f))
                } else {
                    multiPoints.toList()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // Multi-point UI
    // ─────────────────────────────────────────────────────

    private fun addMultiPoint() {
        val id = nextPointId++
        val point = ClickPoint(id, 540f, 960f + id * 100)
        multiPoints.add(point)

        val row = layoutInflater.inflate(R.layout.item_multi_point, llMultiPoints, false)
        row.tag = id
        val tvLabel = row.findViewById<TextView>(R.id.tvPointLabel)
        val etX = row.findViewById<EditText>(R.id.etPointX)
        val etY = row.findViewById<EditText>(R.id.etPointY)
        val etDelay = row.findViewById<EditText>(R.id.etPointDelay)
        val btnRemove = row.findViewById<Button>(R.id.btnRemovePoint)

        tvLabel.text = "Point$id"
        etX.setText(point.x.toInt().toString())
        etY.setText(point.y.toInt().toString())
        etDelay.setText("0")

        etX.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val x = etX.text.toString().toFloatOrNull() ?: point.x
                val idx = multiPoints.indexOfFirst { it.id == id }
                if (idx >= 0) multiPoints[idx] = multiPoints[idx].copy(x = x)
            }
        }
        etY.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val y = etY.text.toString().toFloatOrNull() ?: point.y
                val idx = multiPoints.indexOfFirst { it.id == id }
                if (idx >= 0) multiPoints[idx] = multiPoints[idx].copy(y = y)
            }
        }
        etDelay.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val d = etDelay.text.toString().toLongOrNull() ?: 0L
                val idx = multiPoints.indexOfFirst { it.id == id }
                if (idx >= 0) multiPoints[idx] = multiPoints[idx].copy(delayAfterMs = d)
            }
        }
        btnRemove.setOnClickListener {
            llMultiPoints.removeView(row)
            multiPoints.removeAll { it.id == id }
        }
        llMultiPoints.addView(row)
    }

    // ─────────────────────────────────────────────────────
    // Permission UI
    // ─────────────────────────────────────────────────────

    private fun refreshPermissionsUI() {
        val overlayOk = hasOverlayPermission()
        val accessOk  = ClickAccessibilityService.isActive()

        tvPermOverlay.text = if (overlayOk) "✓ 懸浮窗權限：已授予" else "✗ 懸浮窗權限：未授予"
        tvPermOverlay.setTextColor(if (overlayOk) 0xFF00CC66.toInt() else 0xFFFF4444.toInt())
        btnGrantOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        tvPermAccessibility.text = if (accessOk) "✓ 無障礙服務：已啟用" else "✗ 無障礙服務：未啟用"
        tvPermAccessibility.setTextColor(if (accessOk) 0xFF00CC66.toInt() else 0xFFFF4444.toInt())
        btnGrantAccessibility.visibility = if (accessOk) View.GONE else View.VISIBLE

        cardSettings.isEnabled = overlayOk && accessOk
        btnStartOverlay.isEnabled = overlayOk
    }

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    // ─────────────────────────────────────────────────────
    // State Observer
    // ─────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            RuntimeState.snapshot.collectLatest { snap ->
                tvStatus.text = when (snap.state) {
                    RunState.RUNNING -> "RUNNING"
                    RunState.PAUSED  -> "PAUSED"
                    RunState.STOPPED -> "STOPPED"
                }
                tvClickCount.text = snap.stats.totalClicks.toString()
                tvCps.text = "%.1f clicks/s".format(snap.stats.actualCps)
            }
        }
    }
}
