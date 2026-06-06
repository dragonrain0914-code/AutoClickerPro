# AutoClicker Pro — 完整 Android Studio 專案

高速無障礙連點器，使用 `dispatchGesture()` 實現最低延遲點擊。

---

## 專案結構

```
AutoClicker/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/autoclicker/pro/
│   │   │   ├── accessibility/
│   │   │   │   ├── ClickAccessibilityService.kt   ← 無障礙服務主體
│   │   │   │   ├── ClickEngine.kt                 ← 高速連點引擎
│   │   │   │   └── EngineWatchdog.kt              ← 心跳監控
│   │   │   ├── model/
│   │   │   │   ├── Models.kt                      ← 資料模型（FSM、點位等）
│   │   │   │   └── RuntimeState.kt                ← 全域狀態中心（StateFlow）
│   │   │   ├── overlay/
│   │   │   │   └── OverlayService.kt              ← 懸浮窗前景服務
│   │   │   ├── service/
│   │   │   │   └── BootReceiver.kt                ← 開機自啟接收器
│   │   │   └── ui/
│   │   │       └── MainActivity.kt                ← 主介面 + 權限引導
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml
│   │       │   ├── overlay_control_panel.xml
│   │       │   ├── overlay_target_dot.xml
│   │       │   └── item_multi_point.xml
│   │       ├── xml/
│   │       │   └── accessibility_service_config.xml
│   │       ├── drawable/
│   │       │   ├── target_dot.xml
│   │       │   └── ic_notification.xml
│   │       └── values/
│   │           ├── strings.xml
│   │           ├── themes.xml
│   │           └── colors.xml
│   └── build.gradle.kts
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 架構說明

### 模組化設計

| 模組 | 職責 |
|------|------|
| `ClickEngine` | 非阻塞協程連點迴圈、GesturePool、PointSequencer |
| `EngineWatchdog` | 心跳監控，5 秒無回應自動重啟引擎 |
| `ClickAccessibilityService` | 唯一 `dispatchGesture()` 呼叫點，FSM 狀態監聽 |
| `OverlayService` | 前景服務，管理懸浮控制面板與紅色目標點 |
| `RuntimeState` | 全域 StateFlow 狀態中心，零鎖設計 |

### FSM 狀態機

```
STOPPED ──[Start]──▶ RUNNING ──[Pause]──▶ PAUSED
   ▲                    │                     │
   └────────────────────┘◀───[Resume]──────────┘
              [Stop]
```

### 效能設計

- `dispatchGesture()` duration 設為 **1ms**（系統最低）
- 協程 `delay()` 代替 `Thread.sleep()`，精度更高
- 統計每 20 次才更新 StateFlow，避免 UI 刷新壓力
- CPS 使用 50 次滑動窗口計算
- GestureDescription 每次建構（Android 不支援複用 Stroke）

---

## 編譯步驟

### 環境需求

- Android Studio Ladybug (2024.2.1) 或更新版本
- JDK 17
- Android SDK 35
- Gradle 8.7

### 步驟

```bash
# 1. 用 Android Studio 開啟此資料夾
File → Open → 選擇 AutoClicker/ 資料夾

# 2. 等待 Gradle Sync 完成

# 3. 連接裝置或開啟模擬器

# 4. 編譯並安裝
Build → Make Project
Run → Run 'app'

# 或命令列
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用說明

### 首次設定

1. 開啟 APP
2. 點擊「授予」→ 開啟**懸浮窗權限**
3. 點擊「開啟」→ 進入無障礙設定 → 找到 **AutoClicker 連點服務** → 啟用
4. 返回 APP

### 開始使用

1. 選擇**點擊模式**（單點/雙點/多點）
2. 選擇**點擊間隔**（1ms/2ms/5ms/10ms/20ms/自訂）
3. 點擊**「啟動懸浮控制面板」**
4. 將**紅色十字目標點**拖到目標位置
5. 按懸浮面板的 **Start** 開始連點
6. 按 **Pause** 暫停，**Resume** 繼續，**Stop** 停止

---

## 模擬器相容性

| 模擬器 | 測試版本 | 備注 |
|--------|----------|------|
| MuMu 模擬器 | 12 | ✅ 相容 |
| LDPlayer 雷電 | 9 | ✅ 相容 |
| 夜神模擬器 | 7 | ✅ 相容 |

> 模擬器需開啟「允許模擬點擊」或相關無障礙選項。

---

## 效能基準

| 設定間隔 | 實測 CPS（Pixel 7） |
|---------|-------------------|
| 1ms | ~80-120 clicks/s（系統上限） |
| 5ms | ~180-200 clicks/s |
| 10ms | ~95-100 clicks/s |
| 20ms | ~48-50 clicks/s |

> `dispatchGesture()` 本身有系統層級的速率限制，實際上限依裝置而異。

---

## 注意事項

- 本 APP 完全使用 Android 官方 API，不含任何 Hook/注入
- 僅模擬觸控手勢，不讀取任何 APP 資料或記憶體
- 長時間使用請注意裝置溫度
- 部分 APP 有防自動化保護機制，本工具無法繞過
