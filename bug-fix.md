# Bug 修复记录

---

## Bug #1: Debug 模式下 ANR（"忘打卡" 无响应弹窗）

**发现时间**: 2026-04-14
**严重程度**: 高（导致 App 弹出无响应对话框，影响正常使用）

### 问题描述

打开 Debug 模式后，App 频繁弹出"忘打卡 无响应。是否将其关闭？"的系统弹窗，导致用户需要等待或强制关闭。

### 根因分析

`MainActivity.requestDebugLocation()` 方法在**主线程（UI 线程）**中执行了阻塞操作：

```java
// 主线程上执行：
debugLocationManager.requestLocationUpdates(GPS_PROVIDER, 0, 0, debugLocationListener);

while (debugCurrentLocation == null && (System.currentTimeMillis() - startTime) < 8_000) {
    Thread.sleep(500);  // ← 主线程被阻塞，最长阻塞 8 秒
}
```

Android 主线程阻塞超过 5 秒会触发 ANR（Application Not Responding）检测，弹出强制关闭对话框。

### 修复方案

1. 在 `MainActivity` 中添加独立的 `HandlerThread`（`DebugLocationThread`）
2. 将 `requestDebugLocation()` 的调度从 `debugHandler`（主线程 Handler）改为 `debugBackgroundHandler`（后台线程 Handler）
3. GPS 请求、`Thread.sleep()` 等待、`checkDebugGeofence()` 调用全部在后台线程执行
4. `onPause()` 时 `quit()` 后台线程，`onResume()` 时重新创建

### 涉及文件

- `MainActivity.java`

---

## Bug #2: Debug 模式下距离 7 米但卡片状态显示"已离开"

**发现时间**: 2026-04-14
**严重程度**: 高（状态显示不准确，用户无法判断当前是否在打卡范围内）

### 问题描述

Debug 模式下，顶部 GPS 信息栏显示当前位置距离打卡点 **7 米**（小于设定的 20 米半径），但卡片上的状态仍然显示"已离开"，未更新为"已进入"。

### 根因分析

`checkDebugGeofence()` 方法确实计算了距离并更新了数据库中的状态：

```java
// 数据库中的状态已更新为 "inside"
database.locationDao().updateStatus(entity.id, "inside");
```

但 `MainActivity` 中 `RecyclerView` 使用的 `locationList` 只在 `onCreate()` 时从数据库加载了一次：

```java
// onCreate() 中只加载一次
loadLocationsFromDatabase();

// 之后 checkDebugGeofence() 只更新 DB，没有刷新 locationList
```

导致适配器显示的始终是旧的内存数据，即使 DB 已更新为 `inside`，卡片仍显示 `outside` 或 `unknown`。

### 修复方案

在 `checkDebugGeofence()` 中检测到状态变化后，调用 `loadLocationsFromDatabase()` 从数据库重新加载整个列表到内存：

```java
// checkDebugGeofence() 末尾：
if (statusChanged) {
    mainHandler.post(() -> loadLocationsFromDatabase());
}
```

### 涉及文件

- `MainActivity.java`

---

## Bug #3: 右上角监控滑块颜色显示为白色

**发现时间**: 2026-04-14
**严重程度**: 中（颜色不生效，用户无法通过颜色判断开关状态）

### 问题描述

右上角监控滑块开启时应该是红色，关闭时应该是灰色，但实际显示为纯白色。

### 根因分析

布局文件中使用了 `android:thumbTint` 和 `android:trackTint` 属性，这两个属性从 **API 29** 开始支持。项目的 `minSdk` 是 **21**，在低版本 Android 上这些属性被忽略。

必须使用 `app:` 命名空间（`app:thumbTint` / `app:trackTint`）才能兼容 API 21+。

### 修复方案

1. 布局根元素添加 `xmlns:app="http://schemas.android.com/apk/res-auto"` 命名空间
2. 将 `android:thumbTint` 改为 `app:thumbTint`
3. 将 `android:trackTint` 改为 `app:trackTint`

### 涉及文件

- `activity_main.xml`

---

## Bug #4: Debug 模式下顶部 GPS 坐标和更新时间显示为空

**发现时间**: 2026-04-14
**严重程度**: 高（Debug 模式核心功能不可用）

### 问题描述

打开 Debug 模式后，页面顶部的 GPS 信息栏（`GPS: 39.904200, 116.407400` 和 `更新时间: 14:32:05`）始终显示为空或未获取状态。

### 根因分析

修复 Bug #1 时将 GPS 轮询逻辑移到了后台线程（`HandlerThread`），但 `updateDebugUI()` 和 `adapter.setCurrentLocation()` 仍然直接在后台线程中调用。Android 不允许在后台线程更新 UI 控件，触发 `CalledFromWrongThreadException`，该异常被外层的 `try-catch(Exception e)` 吞掉，导致 UI 更新静默失败。

```java
// 后台线程中执行（错误）：
updateDebugUI();           // ← 更新 TextView → CalledFromWrongThreadException
adapter.setCurrentLocation();  // ← notifyDataSetChanged → 也可能触发 UI 异常
```

### 修复方案

将 UI 更新操作通过 `mainHandler.post()` 切换到主线程执行：

```java
// 正确：后台线程获取位置，主线程更新 UI
final Location loc = debugCurrentLocation;
mainHandler.post(() -> {
    updateDebugUI();
    adapter.setCurrentLocation(loc);
});
```

### 涉及文件

- `MainActivity.java`

---

## v1.4 新增功能：闹铃声音提醒开关

**发布时间**: 2026-04-14
**功能编号**: PLAN.md 功能清单 #3.3

### 功能描述

设置页面新增「🔔 闹铃声音」开关（默认开启），控制提醒时是否播放闹铃声音。

### 实现逻辑

播放闹铃声音需要**同时满足两个条件**：
1. 闹铃声音开关在设置中为 **开启** 状态
2. 系统铃声模式为 **正常**（`RINGER_MODE_NORMAL`），即非静音、非震动模式

```
shouldPlayAlarmSound(context):
    ├── 检查设置中闹铃开关 → 关闭则返回 false
    └── 检查系统 AudioManager.getRingerMode()
        ├── RINGER_MODE_NORMAL (2) → 返回 true ✅ 播放闹铃
        ├── RINGER_MODE_VIBRATE (1) → 返回 false ❌ 系统震动模式，不播放
        └── RINGER_MODE_SILENT (0)  → 返回 false ❌ 系统静音，不播放
```

### 涉及文件

- `SettingsActivity.java` — 新增 `KEY_ALARM_SOUND`、开关 UI、`shouldPlayAlarmSound()` 方法
- `activity_settings.xml` — 新增闹铃声音开关项
- `LocationMonitorService.java` — `startCountdownTimer()` 中闹铃播放前增加 `shouldPlayAlarmSound()` 检查
- `MainActivity.java` — `startDebugCountdown()` 中同样增加检查，新增 `RingtoneManager`、`Uri` 导入

---

## v1.4.1 Bug Fixes (2026-04-15)

### Bug #5: 倒计时结束后没有触发第二次提醒

**发现时间**: 2026-04-15
**严重程度**: 高（倒计时提醒功能完全失效，用户错过打卡提醒）

#### 问题描述

停留在 App 主界面时，触发地理围栏提醒后弹窗显示"10秒倒计时提醒即将开始..."，用户点击"知道了"关闭对话框后，10秒倒计时结束，但没有任何后续提醒（没有震动、没有通知、没有二次弹窗）。

#### 根因分析

`LocationMonitorService.showForegroundDialog()` 中，对话框的"知道了"按钮调用了 `countdownTimer.cancel()`：

```java
.setPositiveButton("知道了", (dialog, which) -> {
    if (countdownTimer != null) {
        countdownTimer.cancel();  // ← 倒计时被取消，onFinish() 永远不会执行
    }
    dialog.dismiss();
})
```

用户点击"知道了"只是关闭了初始弹窗，但倒计时被同时取消，导致 `onFinish()` 中的二次提醒（震动 + 通知）永远不会触发。

#### 修复方案

移除 `countdownTimer.cancel()` 调用，让倒计时独立于对话框运行：

```java
.setPositiveButton("知道了", (dialog, which) -> {
    // Do NOT cancel countdown timer — let it run to trigger follow-up reminder
    dialog.dismiss();
})
```

`MainActivity.showDebugReminderDialog()` 同样修复。

#### 涉及文件

- `LocationMonitorService.java`
- `MainActivity.java`

---

### Bug #6: 后台运行时 GPS 轮询停止，状态不更新

**发现时间**: 2026-04-15
**严重程度**: 高（后台监控完全失效，用户离开/进入围栏时无任何提醒）

#### 问题描述

停留在 App 主界面或让 App 在后台运行时，即使进出电子围栏，卡片状态也不会变化。只有从通知栏点击进入 App 后，状态才会更新。

#### 根因分析

`LocationMonitorService` 使用 `HandlerThread` 每 15 秒轮询 GPS。当手机屏幕关闭后，Android 的 **Doze 模式** 会让 CPU 进入深度睡眠状态，`HandlerThread` 被挂起，GPS 请求不再执行。

当用户从通知栏点击打开 App 时，屏幕亮起、CPU 唤醒，`HandlerThread` 恢复运行，此时才会执行一次 GPS 轮询并更新状态。

#### 修复方案

使用 `PowerManager.WakeLock`（`PARTIAL_WAKE_LOCK`）在每次 GPS 轮询期间保持 CPU 唤醒：

1. **`onCreate()`**: 创建 WakeLock 实例
2. **`checkLocations()` 开始**: 获取 WakeLock（`wakeLock.acquire(30_000)`，30 秒超时作为安全保护）
3. **`checkLocations()` 结束**: 释放 WakeLock
4. **异常/无位置**: 提前返回时也释放 WakeLock
5. **`onDestroy()`**: 确保 WakeLock 被释放
6. **`AndroidManifest.xml`**: 添加 `WAKE_LOCK` 权限

```
checkLocations()
    ↓
    wakeLock.acquire(30_000)  ← 保持 CPU 唤醒
    ↓
    请求 GPS → 等待 → 计算距离 → 更新状态 → 触发提醒
    ↓
    wakeLock.release()  ← 释放，允许 CPU 睡眠
```

**电量影响**: WakeLock 只在每次 15 秒 GPS 轮询期间持有（通常 < 1 秒），对电量影响极小。

#### 涉及文件

- `LocationMonitorService.java`
- `AndroidManifest.xml`

---

### Bug #7: Android 14 上监控已开启，但退到后台后不轮询；点通知回前台才立即触发提醒

**发现时间**: 2026-04-16
**严重程度**: 高（Android 11+ / 14 上后台监控看似开启，实际无法获得位置更新）

#### 问题描述

在 Android 14 手机上安装 v1.4.1 后，用户开启主界面右上角的监控并将 App 置于后台，到了上班/下班时间范围内没有任何自动 GPS 轮询迹象，卡片状态也不刷新。

但点击通知栏中的“正在监控打卡位置”进入 App 后，会立刻执行一次定位、刷新打卡点状态，并在满足条件时马上弹出前台提醒，倒计时提醒也能正常完成。

#### 根因分析

这次问题的核心不是 `LocationMonitorService.scheduleNextCheck()` 或 `WakeLock` 失效，而是 **Android 11+ 后台定位权限流程不完整**。

`MainActivity` 在开启监控时虽然检查了 `ACCESS_BACKGROUND_LOCATION`，但在 Android 11+ 上，后台定位通常不能再依赖普通运行时弹窗稳定授予，用户必须到系统设置里把位置权限改成 **“始终允许”**。

因此实际出现了下面的行为差异：

1. **App 在前台**：已有 `ACCESS_FINE_LOCATION`，所以点开通知回到 `MainActivity` 后，前台定位立即成功
2. **App 在后台**：没有真正的“始终允许”后台定位权限，`LocationMonitorService` 无法拿到新的位置更新

这就表现成“监控通知在，但后台不轮询；一回前台马上恢复”。

#### 修复方案

在 `MainActivity` 中重构“开启监控”的权限流，并补充可见提示：

1. 新增独立的 `SERVICE_LOCATION_PERMISSION_REQUEST_CODE`，将“开启监控”与“获取当前位置”两条权限流分开
2. 新增 `startMonitoringServiceWithPermissionCheck()` / `hasRequiredMonitoringPermissions()` / `requestBackgroundLocationPermission()`
3. Android 10：继续使用运行时请求 `ACCESS_BACKGROUND_LOCATION`
4. Android 11+：直接跳转系统应用设置页，引导用户将位置权限改成“始终允许”
5. 用户从设置页返回 `MainActivity.onResume()` 后，如果权限已补齐，则自动继续启动监控服务
6. 主界面新增顶部权限提示条 `layoutPermissionWarning`，在缺少后台定位时明确提示“否则退到后台后不会轮询”
7. 监控开关增加受控回写，避免 `setChecked()` 触发监听器递归，错误清掉待启动状态

#### 涉及文件

- `MainActivity.java`
- `activity_main.xml`
- `README.md`
- `QWEN.md`
- `PLAN.md`
- `CODE_LOGIC.md`

---

## v1.5 新增功能：告警日志 (2026-04-20)

### 功能描述

主界面新增 Tab 切换页，用于记录和查看每次告警事件，便于事后查看是否有漏报的提醒。

### 实现方案

#### 数据库层
- 新增 `alert_logs` 表，存储告警日志
- `AlertLogEntity`: 包含 `id`, `locationId`, `locationName`, `alertType`, `latitude`, `longitude`, `distance`, `triggeredAt`
- `AlertLogDao`: 提供 `insertLog()`, `getAllLogs()`, `deleteOldLogs()` 等方法
- `AppDatabase` 升级到 version 3，添加 `alertLogDao()`

#### UI 层
- `activity_main.xml` 新增 `TabLayout`（"打卡地点" / "告警日志"）
- 新增 `recyclerViewLogs` RecyclerView 和 `tvLogEmpty` 空状态视图
- `AlertLogAdapter`: 日志列表适配器，显示告警类型、地点、时间等信息
- `MainActivity`: Tab 切换逻辑，`loadLogsFromDatabase()` 加载日志

#### 服务层
- `LocationMonitorService.triggerReminder()` 前调用 `logAlert()` 写入日志
- `MainActivity.checkDebugGeofence()` 同样记录 Debug 模式触发的告警

### 涉及文件

- `AlertLogEntity.java` — 新增
- `AlertLogDao.java` — 新增
- `AlertLogAdapter.java` — 新增
- `item_alert_log.xml` — 新增
- `AppDatabase.java` — 新增 alertLogDao()，version 3
- `LocationMonitorService.java` — 新增 logAlert()
- `MainActivity.java` — 新增 Tab 切换、日志加载、logDebugAlert()
- `activity_main.xml` — 新增 TabLayout、RecyclerView、tvLogEmpty

---

## v1.5 Bug Fixes (2026-04-20)

### Bug #8: Debug 模式下距离正确但状态显示不正确

**发现时间**: 2026-04-20
**严重程度**: 高（Debug 模式核心功能状态显示不准确）

#### 问题描述

开启 Debug 模式后，打卡点距离显示 3 米（远小于设定的 20 米半径），但打卡点卡片状态仍显示"已离开"，未更新为"已进入"。

#### 根因分析

`checkDebugGeofence()` 中虽然调用了 `loadLocationsFromDatabase()` 从数据库重新加载打卡点数据，但 `adapter.setLocations()` 后未主动调用 `notifyDataSetChanged()` 确保 RecyclerView 强制刷新。

#### 修复方案

1. 在 `loadLocationsFromDatabase()` 的 mainHandler post 回调中，`setLocations()` 后额外调用 `notifyDataSetChanged()`
2. 同时确保 `setCurrentLocation()` 在 `loadLocationsFromDatabase()` 后被调用，以刷新距离显示
3. 在 `checkDebugGeofence()` 的 statusChanged 分支中，也添加 `adapter.notifyDataSetChanged()` 调用

#### 涉及文件

- `MainActivity.java`

---

### Bug #9: 后台运行时只显示对话框而非通知栏通知

**发现时间**: 2026-04-20
**严重程度**: 高（后台提醒方式错误，用户无法看到通知）

#### 问题描述

App 退到后台后，当触发地理围栏提醒时，应该显示通知栏通知，但实际却发送了前台对话框（只有在从通知栏点回 App 后才看到对话框）。

#### 根因分析

`LocationMonitorService.isAppInForeground()` 通过检查进程状态判断是否在前台：
- 当 App 退到后台但 `LocationMonitorService` 作为前台服务运行时，进程仍然是 `IMPORTANCE_FOREGROUND`
- 导致 `isAppInForeground()` 返回 `true`，错误地发送对话框而非通知

#### 修复方案

1. 在 `LocationMonitorService` 中新增静态标志 `isActivityVisible`
2. 在 `MainActivity.onResume()` 中设置 `LocationMonitorService.setActivityVisible(true)`
3. 在 `MainActivity.onPause()` 中设置 `LocationMonitorService.setActivityVisible(false)`
4. 修改 `isAppInForeground()`，先检查 `isActivityVisible` 标志，若为 false 直接返回 false
5. 优化 `sendReminderNotification()` 使用时间戳作为通知 ID（替代固定 ID），避免通知被覆盖

#### 涉及文件

- `LocationMonitorService.java` — 新增 isActivityVisible 标志和相关方法，修改 isAppInForeground()
- `MainActivity.java` — onResume/onPause 中更新 Activity 可见性状态

---

### Bug #10: 主界面打卡点状态不刷新（非 Debug 模式）

**发现时间**: 2026-04-20
**严重程度**: 高（主界面状态显示与实际不符）

#### 问题描述

不开启 Debug 模式时，即使 `LocationMonitorService` 正在后台运行且数据库中的打卡点状态已更新，主界面上的打卡点状态卡片仍显示旧状态，不自动刷新。只有从通知栏点击进入 App 后状态才会改变。

#### 根因分析

`MainActivity.startPeriodicRefresh()` 每 10 秒只调用 `updateServiceStatus()` 和 `updatePermissionWarning()`，**没有调用 `loadLocationsFromDatabase()`** 来重新加载打卡点列表并刷新 RecyclerView。

而 `LocationMonitorService.checkLocations()` 虽然更新了数据库中的状态，但主界面不会主动重新加载数据。

#### 修复方案

在 `startPeriodicRefresh()` 的刷新任务中，当 Activity 可见且当前 Tab 为"打卡地点"时，调用 `loadLocationsFromDatabase()` 重新加载打卡点列表数据。

```java
if (LocationMonitorService.isActivityVisible && tabLayout != null && tabLayout.getSelectedTabPosition() == 0) {
    loadLocationsFromDatabase();
}
```

#### 涉及文件

- `MainActivity.java` — startPeriodicRefresh() 中新增 loadLocationsFromDatabase() 调用

---

## Bug #11: Android 14 后台 GPS 轮询停止（荣耀50）

**发现时间**: 2026-04-28
**严重程度**: 高（后台监控失效，熄屏后不轮询 GPS）

#### 问题描述

荣耀50（Android 14）开启监控后放置后台，通知栏显示"正在监控"，但进出围栏时没有任何提醒。熄屏放置后 GPS 轮询停止。

#### 根因分析

1. **Doze + App Standby**: Android 14 对后台服务的限制更严格，HandlerThread 在屏幕关闭后可能被挂起
2. **电池优化**: 系统默认将第三方 app 置于"不优化"状态，但即使加了 WakeLock，深度 Doze 仍可能延迟轮询
3. **无保活机制**: 纯 HandlerThread + WakeLock 在 Android 14 上不够可靠

#### 修复方案

1. **请求电池优化白名单**: 在 `onCreate()` 中调用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，引导用户将 app 加入白名单
2. **AlarmManager 备份机制**: 每次 `scheduleNextCheck()` 时同时设置一个 60 秒后的 Alarm，如果主线程被延迟，Alarm 会唤醒服务执行 `checkLocations()`
3. **响应 BACKUP_CHECK action**: `onStartCommand()` 检测到 `BACKUP_CHECK` action 时立即执行 GPS 检查

#### 涉及文件

- `LocationMonitorService.java` — 新增 `scheduleBackupAlarm()`, `requestBatteryOptimizationExemption()`, 处理 `BACKUP_CHECK` action
- `AndroidManifest.xml` — 已在现有配置中（`RECEIVE_BOOT_COMPLETED` 已存在）
