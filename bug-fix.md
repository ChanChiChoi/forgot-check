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
