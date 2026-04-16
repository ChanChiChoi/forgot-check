# 忘打卡 — 代码逻辑详解 (v1.3)

> 本文档逐文件、逐方法梳理当前实现的所有逻辑细节，便于对照预期发现偏差。

---

## 一、整体架构

```
MainActivity (前台 Activity)
    ├── RecyclerView 列表（打卡地点卡片）
    ├── 服务开关 SwitchCompat（启动/停止 LocationMonitorService）
    ├── Debug 模式（GPS 15s 轮询 + 地理围栏判断 + 提醒）
    └── 添加/编辑对话框（名称、坐标、半径、时间范围）

LocationMonitorService (后台 ForegroundService)
    ├── 15s 主动请求 GPS（非 getLastKnownLocation）
    ├── 智能轮询策略（根据时间范围开关决定是否轮询）
    ├── 地理围栏判断 + 状态机（inside/outside/unknown）
    └── 提醒触发（震动 2s + 弹窗/通知 + 倒计时）

SettingsActivity (设置页面)
    ├── 震动提醒（默认 ON）
    ├── 弹窗提醒（默认 ON）
    ├── 通知提醒（默认 ON）
    ├── 倒计时提醒（默认 ON，10s 可配置 1-300）
    └── Debug 模式（默认 OFF）

BootReceiver
    └── 开机自启动 LocationMonitorService

AppDatabase (Room)
    └── checkin_locations 表（version 2, fallbackToDestructiveMigration）
```

---

## 二、数据模型

### 2.1 CheckInLocationEntity（Room 实体）

**数据库表**: `checkin_locations`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `id` | long (autoGenerate) | — | 主键 |
| `name` | String | — | 地点名称 |
| `latitude` | double | — | 纬度 |
| `longitude` | double | — | 经度 |
| `radiusMeters` | int | — | 半径（米） |
| `enabled` | boolean | `true` | 是否启用 |
| `status` | String | `"unknown"` | `"inside"` / `"outside"` / `"unknown"` |
| `enterTimeEnabled` | boolean | `false` | 上班时间范围开关 |
| `enterTimeStart` | String | `"08:50"` | 上班开始时间 |
| `enterTimeEnd` | String | `"09:03"` | 上班结束时间 |
| `leaveTimeEnabled` | boolean | `false` | 下班时间范围开关 |
| `leaveTimeStart` | String | `"17:50"` | 下班开始时间 |
| `leaveTimeEnd` | String | `"18:10"` | 下班结束时间 |

**转换方法**:
- `toCheckInLocation()` → 转为领域模型 `CheckInLocation`
- `fromCheckInLocation()` → 从领域模型创建实体

### 2.2 CheckInLocation（领域模型）

与 Entity 字段相同，但独立于 Room，用于业务逻辑。

**时间范围判断方法**:

```java
boolean isInEnterTimeWindow() {
    if (!enterTimeEnabled) return true;  // 开关OFF = 任何时间都提醒
    return isTimeInRange(enterTimeStart, enterTimeEnd);
}

boolean isInLeaveTimeWindow() {
    if (!leaveTimeEnabled) return true;  // 开关OFF = 任何时间都提醒
    return isTimeInRange(leaveTimeStart, leaveTimeEnd);
}

boolean isTimeInRange(String start, String end) {
    // 将时间转为分钟数比较
    // 支持跨午夜（如 23:00 ~ 01:00）
    // 解析失败时返回 true（兜底）
}
```

**关键逻辑**: 时间范围开关 **OFF = 不限时间，始终提醒**；开关 **ON = 仅在规定时间段内提醒**。

---

## 三、数据库层

### 3.1 AppDatabase

- Room 数据库，版本 2
- 使用 `fallbackToDestructiveMigration()`（schema 变更时删除重建）
- 单例模式（双重检查锁）
- 数据库文件名: `checkin_database`

### 3.2 CheckInLocationDao

| 方法 | SQL | 说明 |
|------|-----|------|
| `getAllLocations()` | `SELECT * ORDER BY id DESC` | 获取全部地点 |
| `getLocationById(id)` | `SELECT * WHERE id = :id` | 按 ID 查询 |
| `insertLocation()` | `INSERT` | 插入，返回新 ID |
| `updateLocation()` | `UPDATE` | 更新全部字段 |
| `deleteLocation()` | `DELETE` | 删除 |
| `updateEnabled(id, enabled)` | `UPDATE SET enabled` | 更新启用状态 |
| `updateStatus(id, status)` | `UPDATE SET status` | 更新进出状态 |
| `updateTimeRanges(...)` | `UPDATE SET enter/leave time` | 更新时间范围 |
| `deleteAll()` | `DELETE ALL` | 清空表 |

---

## 四、MainActivity 详解

### 4.1 生命周期

```
onCreate()
    ├── findViewById + 初始化 RecyclerView/Adapter
    ├── switchServiceStatus.setOnCheckedChangeListener() ← 直接控制服务
    ├── toolbar.setOnClickListener() ← 也触发 switch 切换
    ├── Help/Settings 按钮
    ├── setupSwipeToDelete() ← 左滑删除
    ├── updateEmptyView() / updateServiceStatus()
    ├── requestPermissions() ← 通知/后台位置/悬浮窗权限
    ├── 初始化数据库 + 线程池
    ├── loadLocationsFromDatabase()
    └── 初始化 Debug 模式（LocationManager + Listener + Runnable）

onResume()
    ├── updateServiceStatus()
    ├── updateDebugModeVisibility() ← 如果 Debug ON 则开始 GPS 轮询
    └── updatePermissionWarning() ← 缺少后台定位时显示顶部提示；若刚从设置页返回且权限已补齐，则继续启动 Service

onPause()
    └── stopDebugLocation() ← 暂停时停止 Debug GPS（省电）
```

### 4.2 服务开关逻辑

```
用户点击 SwitchCompat 或 Toolbar
    ↓
setOnCheckedChangeListener(isChecked)
    ↓
    isChecked = true:
        ├── startMonitoringServiceWithPermissionCheck()
        │   ├── 检查 ACCESS_FINE_LOCATION → 无则请求权限，回退 switch
        │   ├── 检查 ACCESS_BACKGROUND_LOCATION
        │   │   ├── Android 10: 直接请求运行时后台定位权限
        │   │   └── Android 11+: 跳系统应用设置页，要求用户改成“始终允许”
        │   ├── onResume() 时重新检查权限
        │   └── 权限齐全后 startMonitoringService()
        ├── 顶部显示/隐藏后台定位权限提示条
        └── Toast "位置监控已启动"
    ↓
    isChecked = false:
        ├── LocationMonitorService.stopService()
        ├── isServiceRunning = false
        └── Toast "位置监控已停止"
```

**Android 11+ 关键细节**:
- 仅有前台定位权限时，前台界面里的定位可以成功，但 `LocationMonitorService` 退到后台后可能拿不到位置更新
- 这会表现为：监控通知存在，但状态不刷新；一旦点击通知回到 `MainActivity`，马上能定位并触发提醒
- 主界面顶部 `layoutPermissionWarning` 用于显式提示这种权限缺口，避免误判成 15 秒轮询定时器失效

### 4.3 Debug 模式 GPS 轮询（重点）

**轮询间隔**: **15 秒**（非 10 秒）

```
updateDebugModeVisibility()
    ↓
    Debug ON → startDebugLocation()
        ↓
        debugHandler.post(debugLocationRunnable)
            ↓
            requestDebugLocation()
                ├── requestLocationUpdates(GPS_PROVIDER) 主动请求
                ├── 阻塞等待最多 8 秒（每次 sleep 500ms 检查）
                ├── 获取到位置后 removeUpdates()
                ├── updateDebugUI() ← 更新顶部 GPS 信息
                ├── adapter.setCurrentLocation() ← 更新卡片距离
                └── checkDebugGeofence() ← 地理围栏判断
                ↓
                debugHandler.postDelayed(this, 15_000) ← 15 秒后再次执行

Debug OFF → stopDebugLocation()
    ├── removeCallbacks
    ├── removeUpdates
    ├── debugCurrentLocation = null
    └── debugCountdownTimer.cancel()
```

**关键细节**:
- GPS 请求后立即 `removeUpdates()`，不是持续监听
- 每次轮询阻塞最多 **8 秒**（非 Service 的 10 秒）
- GPS 失败时 fallback 到 `NETWORK_PROVIDER` 的 `getLastKnownLocation()`
- 用户退出/切出 MainActivity（`onPause`）时**停止轮询**

### 4.4 Debug 地理围栏判断（checkDebugGeofence）

```
executorService.execute(后台线程)
    ↓
    从数据库读取全部地点
    ↓
    遍历每个 enabled 地点
        ├── Location.distanceBetween() 计算距离
        ├── isInside = distance <= radiusMeters
        ├── newStatus = isInside ? "inside" : "outside"
        ↓
        if (entity.status != newStatus):  ← 状态变化才触发
            ├── oldStatus = entity.status
            ├── database.locationDao().updateStatus(id, newStatus)  ← 更新 DB
            ↓
            if (oldStatus != "unknown"):
                ├── isInside:
                │   ├── if (!location.isInEnterTimeWindow()) continue  ← 时间窗口过滤
                │   └── "你进入了 XX，请记得打卡！"
                │
                └── !isInside (离开):
                    ├── if (!location.isInLeaveTimeWindow()) continue  ← 时间窗口过滤
                    └── "你离开了 XX，请记得打卡！"
                ↓
                mainHandler.post(() -> triggerDebugReminder(msg))  ← 切主线程提醒
```

**关键细节**:
- 状态写入数据库 → 下次轮询时对比 DB 中的状态 → **不会重复提醒**
- `unknown` 状态不提醒（首次获取位置时不提醒，等你真正离开/进入才提醒）
- 时间窗口判断用的是 `CheckInLocation` 的方法（`isInEnterTimeWindow` / `isInLeaveTimeWindow`）
- **开关 OFF = isInXxxTimeWindow() 返回 true = 始终提醒**

### 4.5 Debug 提醒触发（triggerDebugReminder）

```
读取设置:
    ├── 震动 → 默认 2 秒
    ├── 弹窗 → AlertDialog，标题 "⏰ 打卡提醒 [Debug]"
    ├── 通知 → Debug 模式下不发送通知（只在 Activity 前台）
    └── 倒计时 → CountDownTimer，结束后再次震动 + 弹窗 "[消息] ⏰ 请立即打卡！"
```

**关键细节**:
- Debug 模式**只弹窗不发通知**（因为 Activity 在前台）
- 倒计时结束后**不再走 Service 的提醒逻辑**，直接弹一个简单 AlertDialog
- 倒计时计时器在 `stopDebugLocation()` 时会被 cancel

### 4.6 添加/编辑地点

**添加流程**:
1. 弹出 `dialog_add_location.xml`
2. 可点击 "获取当前位置"（`getLastKnownLocation`，非主动请求）
3. 输入名称、经纬度、半径
4. 时间范围开关默认 **OFF**（不限时间）
5. 默认时间: 上班 08:50~09:03，下班 17:50~18:10
6. 保存 → 写入数据库 → 刷新列表

**编辑流程**:
1. 点击或长按卡片 → 弹出同一个对话框（预填现有值）
2. 可修改所有字段
3. 对话框有 "删除" 按钮（中性按钮）

### 4.7 删除

- 左滑 → `ItemTouchHelper` → 弹出确认对话框 → 删除 DB → 刷新列表
- 编辑对话框中点 "删除" → 同样弹出确认对话框

---

## 五、LocationMonitorService 详解

### 5.1 服务启动/停止

```
startService(Context):
    ├── Android 8+ → startForegroundService()
    ├── Android 7- → startService()
    └── isServiceRunning = true (onCreate 中设置)

stopService(Context):
    └── context.stopService(intent)
        → onDestroy() → isServiceRunning = false
```

### 5.2 GPS 轮询策略

**轮询间隔**: **15 秒**

```
onCreate()
    ├── startForeground(通知)
    ├── 创建 LocationListener（回调设置 currentLocation）
    └── handlerThread → backgroundHandler → post(checkLocationRunnable)

checkLocationRunnable:
    ├── checkLocations()
    └── scheduleNextCheck() → postDelayed(this, 15_000)  ← 始终 15 秒后再次执行
```

**始终轮询**: 不再有时间窗口过滤。Service 运行时始终每 15 秒请求一次 GPS，始终更新状态。

**注意**: 当前代码中 `scheduleNextCheck()` 的两个分支都执行 `postDelayed(this, CHECK_INTERVAL_MS)`，**实际上无论是否应该轮询，15 秒后都会再次执行 `checkLocations()`**。时间窗口过滤在 `checkLocations()` 方法内部判断。也就是说，**GPS 实际上是每 15s 都请求的**（除非无权限），时间窗口只是过滤是否提醒。

### 5.3 checkLocations() 方法

```
1. 检查位置权限 → 无则 return

2. 主动请求 GPS:
   ├── locationManager.requestLocationUpdates(GPS, 0, 0, listener)
   ├── 阻塞等待最多 10 秒（每次 sleep 500ms）
   ├── location = currentLocation
   ├── locationManager.removeUpdates(listener)  ← 省电
   ├── currentLocation = null
   └── GPS 失败 → fallback 到 NETWORK_PROVIDER.getLastKnownLocation()

3. location == null → return

4. 遍历所有 enabled 地点:
   ├── Location.distanceBetween() 计算距离
   ├── isInside = distance <= radius
   ├── newStatus = isInside ? "inside" : "outside"
   ↓
   if (entity.status != newStatus):  ← 状态变化才更新
       ├── oldStatus = entity.status
       ├── updateStatus(entity.id, newStatus)  ← 始终写 DB 更新状态
       ↓
       if (oldStatus != "unknown"):
           ├── 判断时间窗口是否允许:
           │   ├── isInside → isInEnterTimeWindow()?
           │   └── !isInside → isInLeaveTimeWindow()?
           ├── 允许 → triggerReminder()  ← 提醒受时间窗口限制
           └── 不允许 → 不提醒（但状态已更新）
```

**关键细节**:
- GPS 请求后立即 `removeUpdates()`，不是持续监听
- 阻塞等待最长 **10 秒**（Debug 模式是 8 秒）
- `currentLocation = null` 在每次请求后清零，下次重新获取
- 状态变化判断基于 **数据库中的旧状态** vs **当前计算的新状态**
- **状态始终更新**（无论是否在时间窗口内），**只有提醒受时间窗口限制**
- 状态写入数据库后，下次 15s 轮询时不会再重复提醒

### 5.4 提醒触发（triggerReminder）

```
1. 判断 App 是否在前台 (isAppInForeground):
   ├── 前台 + 弹窗开关ON → showForegroundDialog() (AlertDialog)
   └── 后台 + 通知开关ON → sendReminderNotification() (系统通知)

2. 震动开关ON → vibrate() (2秒)

3. 倒计时开关ON → startCountdownTimer()
   ├── 播放闹铃铃声 (TYPE_ALARM)
   ├── 更新现有对话框文本显示倒计时秒数
   ├── CountDownTimer: onTick 无操作, onFinish:
   │   ├── vibrate() (2秒)
   │   └── sendReminderNotification(msg + "⏰ 请立即打卡！")
   └── 5 秒后恢复 Service 正常通知
```

**前台判断**:
```java
isAppInForeground():
    遍历 RunningAppProcessInfo
    如果 processName == 包名 && importance == IMPORTANCE_FOREGROUND → true
```

**Dialog 防重复**:
```java
if (currentDialog != null && currentDialog.isShowing()) return;  // 防止堆叠
```

**Dialog 类型**:
- Android 8+ → `TYPE_APPLICATION_OVERLAY`
- Android 7- → `TYPE_PHONE`
- 需要 `SYSTEM_ALERT_WINDOW` 权限

---

## 六、SettingsActivity 详解

### 6.1 存储

- SharedPreferences 文件名: `checkin_settings`
- 同步保存: 每次 toggle 立即 `apply()`
- `onPause()` 时也保存倒计时秒数

### 6.2 设置项

| Key | 默认值 | 说明 |
|-----|--------|------|
| `vibration_enabled` | `true` | 震动提醒 |
| `popup_enabled` | `true` | 弹窗提醒 |
| `notification_enabled` | `true` | 通知提醒 |
| `countdown_enabled` | `true` | 倒计时提醒 |
| `countdown_seconds` | `10` | 倒计时时长（1-300 秒） |
| `debug_enabled` | `false` | Debug 模式 |

### 6.3 静态 Helper 方法

Service 和 MainActivity 通过以下方法读取设置：

```java
SettingsActivity.isVibrationEnabled(context)
SettingsActivity.isPopupEnabled(context)
SettingsActivity.isNotificationEnabled(context)
SettingsActivity.isCountdownEnabled(context)
SettingsActivity.getCountdownMillis(context)  // 返回毫秒
SettingsActivity.isDebugEnabled(context)
```

---

## 七、BootReceiver

```
收到 BOOT_COMPLETED 广播
    ↓
LocationMonitorService.startService(context)
    ↓
Service 在后台开始 GPS 轮询
```

**注意**: BootReceiver **无条件启动服务**，不受 Debug 开关影响。Debug 开关只影响 MainActivity 中的 GPS 轮询。

---

## 八、CheckInLocationAdapter 详解

### 8.1 卡片显示

| 行 | 内容 |
|----|------|
| 1 | 地点名称 + 启用/禁用 Switch |
| 2 | 纬度/经度坐标 |
| 3 | 半径（米） |
| 4 | 状态（已进入/已离开/未知），颜色不同 |
| 5 | **距离（Debug 模式显示，默认隐藏）** |

### 8.2 距离计算

```
adapter.setCurrentLocation(gpsLocation)  ← 由 Debug 模式调用
    ↓
    currentLocation = gpsLocation
    notifyDataSetChanged()
        ↓
    onBindViewHolder():
        if (currentLocation != null):
            Location.distanceBetween(currentLat, currentLon, checkpointLat, checkpointLon)
            tvDistance.setVisibility(VISIBLE)
            tvDistance.setText("距离: XXX米")
        else:
            tvDistance.setVisibility(GONE)
```

### 8.3 交互

| 操作 | 行为 |
|------|------|
| 点击卡片 | 编辑对话框 |
| 长按卡片 | 编辑对话框 |
| 左滑 | 删除确认对话框 |
| 切换 Switch | 更新 DB enabled 字段 |

---

## 九、提醒时机总结

### 场景 1：Debug 模式关闭，Service 运行中

```
每 15 秒 → GPS 请求 → 遍历所有 enabled 地点
    ↓
    状态变化? (DB oldStatus != newStatus)
        ├── NO → 不提醒（即使距离 < 半径也不提醒）
        └── YES:
            ├── oldStatus = "unknown" → 不提醒（首次定位）
            └── oldStatus != "unknown":
                ├── 进入 + 时间窗口允许 → 提醒 1 次 + 更新状态
                ├── 进入 + 时间窗口不允许 → 不提醒 + 但更新状态
                ├── 离开 + 时间窗口允许 → 提醒 1 次 + 更新状态
                └── 离开 + 时间窗口不允许 → 不提醒 + 但更新状态
```

**不会每 15 秒重复提醒**。只有状态变化时才提醒。**时间窗口只控制是否提醒，不影响状态更新**。

### 场景 2：Debug 模式开启，MainActivity 在前台

```
每 15 秒 → GPS 请求 → 遍历所有 enabled 地点
    ↓
    状态变化? (DB oldStatus != newStatus)
        ├── NO → 不提醒
        └── YES → 同 Service 逻辑，提醒 1 次（受时间窗口限制）
    ↓
    同时更新卡片上的距离显示
```

**同样不会每 15 秒重复提醒**。

### 场景 3：Debug 和监控**互斥**，不会同时运行

- 开启 Debug → 自动停止 Service，监控滑块强制 OFF
- 在 Debug 开启状态下尝试打开监控 → Toast 提示 + 滑块自动回弹
- 关闭 Debug → 用户可以手动打开监控
- **不存在竞争冲突**

---

## 十、潜在问题与注意事项

### 10.1 Service + Debug 互斥机制

Debug 模式和 Service 互斥，**不会出现竞争问题**：
- 开启 Debug 时自动停止 Service
- 在 `updateDebugModeVisibility()` 中检查并停止 Service
- 在监控开关的 `OnCheckedChangeListener` 中拦截 Debug 开启时的操作

### 10.2 Debug 模式下 onPause 停止

当用户切出 MainActivity（进入后台或打开 Settings）：
- `onPause()` → `stopDebugLocation()` → 停止 GPS 轮询
- 此时只有 Service 在后台监控
- 回到 MainActivity → `onResume()` → 重新启动 Debug GPS 轮询

### 10.3 地理围栏判断依赖状态机

状态机 (`inside`/`outside`/`unknown`) 存储在数据库中，**是提醒是否触发的唯一依据**：
- 新位置进入区域 → 状态从 `outside` 变 `inside` → 更新状态 + 时间窗口允许则提醒
- 之后每 15s 仍在区域内 → 状态仍是 `inside` → 不提醒
- 离开区域 → 状态从 `inside` 变 `outside` → 更新状态 + 时间窗口允许则提醒
- **即使时间窗口不允许，状态也会更新**（确保下次进入窗口时状态是正确的）

### 10.4 时间范围开关语义

- **开关 OFF** (`enterTimeEnabled = false`): 不限时间，任何时间进出都提醒
- **开关 ON** (`enterTimeEnabled = true`): 仅在规定时间段内进出才提醒

### 10.5 GPS 请求方式

当前使用 `requestLocationUpdates(provider, 0, 0, listener)` + 阻塞等待：
- Service: 等待 **10 秒**
- Debug: 等待 **8 秒**
- 每次获取后立即 `removeUpdates()`
- **Service 运行时始终每 15 秒请求一次 GPS**（不再有时间窗口过滤）
- 时间窗口只控制是否触发提醒，不影响 GPS 请求和状态更新

### 10.6 震动时长

当前全局震动时长为 **2 秒**（2000ms），包括：
- Service 的 `vibrate()`
- Debug 模式的 `debugVibrate()`

---

## 十一、布局文件概览

### activity_main.xml
- Toolbar（帮助按钮 + 服务开关 Switch + 设置按钮）
- Debug 信息栏（`layoutDebug`，黄色背景，默认 `gone`）
- RecyclerView（打卡地点列表）
- FloatingActionButton（添加地点）

### activity_settings.xml
- ScrollView + 垂直 LinearLayout
- 5 个设置项（震动、弹窗、通知、倒计时、Debug），每项一个 `LinearLayout` + `SwitchCompat`
- 倒计时秒数输入框（`TextInputEditText`）

### item_checkin_location.xml
- CardView
- 名称 + 启用开关
- 坐标、半径、状态
- 距离（Debug 模式显示，默认 `gone`）

### dialog_add_location.xml
- TextInputLayout + TextInputEditText（名称、纬度、经度、半径）
- "获取当前位置"按钮
- 上班时间范围（开关 + 两个时间输入）
- 下班时间范围（开关 + 两个时间输入）
