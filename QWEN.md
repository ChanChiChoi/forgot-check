# Project Context: 忘打卡 (Wang Da Ka)

## Project Overview

基于 GPS 地理围栏的打卡提醒应用。当用户靠近或远离指定坐标一定距离时，通过弹窗或通知提醒打卡，防止上下班忘记打卡。

**Package:** `com.example.helloworld`
**App Name:** 忘打卡

### Tech Stack
- **Language:** Java
- **Build System:** Gradle 8.9
- **Android Gradle Plugin:** 8.7.2
- **Build Environment:** Docker (`mingc/android-build-box:latest`)
- **Minimum SDK:** 21 (Android 5.0)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 34
- **Java Version:** 17
- **UI:** `RecyclerView` + `CardView` + `MaterialComponents`
- **Theme:** `Theme.MaterialComponents.Light.NoActionBar`
- **AndroidX:** Enabled
- **Database:** Room (SQLite)

## Development Progress

| Milestone | Description | Status | APK Version |
|-----------|-------------|--------|-------------|
| **M1** | 清空首页旧代码，搭建打卡地点列表 UI（RecyclerView） | ✅ DONE | `app-debug-m1.apk` |
| **M2** | 实现添加打卡地点功能（获取GPS、输入名称和半径） | ✅ DONE | `app-debug-m2.apk` |
| **M3** | 实现本地数据存储（打卡地点持久化） | ✅ DONE | `app-debug-m3.apk` |
| **M4** | 实现后台位置监控服务（ForegroundService） | ✅ DONE | `app-debug-m4.apk` |
| **M5** | 实现地理围栏判断和提醒逻辑（弹窗 + 通知） | ✅ DONE | `app-debug-m5.apk` |
| **M6** | 实现编辑、删除、启用/禁用功能 | ✅ DONE | `app-debug-m6.apk` |
| **M7** | 实现开机自启动和权限完整处理 | ✅ DONE | `app-debug-m7.apk` |
| **M8** | 测试、优化、打包 | ✅ DONE | `忘打卡-v1.0-release.apk` |
| **v1.1** | 时间范围设置 + 使用说明页面 | ✅ DONE | `app-debug-v1.1.apk` |
| **v1.2** | 设置页面（震动/弹窗/通知/倒计时开关） | ✅ DONE | `忘打卡-v1.2-release.apk` |
| **v1.3** | 主动GPS请求 + 智能轮询 + 滑块UI + Debug模式 + 震动2s | ✅ DONE | `忘打卡-v1.3-release.apk` |

### M1-M7 (v1.0, v1.1, v1.2) — See previous QWEN.md for details

### v1.3 Completed (2026-04-14)
- **Active GPS requests**: Replaced `getLastKnownLocation()` with `requestLocationUpdates()` + `LocationListener`. Actively turns on GPS hardware each cycle and waits up to 10 seconds for a fix.
- **15-second polling interval**: `CHECK_INTERVAL_MS` changed from 30,000 to **15,000** ms.
- **Always-on GPS polling**: Service always requests GPS every 15s when running. **No time window filtering on GPS requests** — status is always updated. Only the **reminder trigger** is gated by time window checks.
- **When service stops** (`onDestroy`): Calls `removeUpdates()` — **completely silent**, no GPS requests at all.
- **Toolbar service status**: Replaced text label ("监控: 运行中"/"监控: 未启动") with a **`SwitchCompat`** toggle switch. Red when ON, grey when OFF.
- **Vibration duration**: Changed from 500ms to **2000ms** (2 seconds) globally.
- **Debug mode** (Settings → `🐛 Debug 模式`, default OFF):
  - When enabled: MainActivity shows yellow GPS info bar at top (coordinates + update time)
  - GPS polling every **15 seconds** in `HandlerThread` background thread (active request, wait up to 8s)
  - Each card shows `距离: XXX米` row
  - Geofence check on each GPS update: triggers reminder on **status change only** (same logic as Service)
  - Reminder respects all settings (vibration, popup, countdown); dialog shows `[Debug]` tag
  - Stops polling when Activity pauses or Debug is turned off
  - **Mutual exclusion with Service**: enabling Debug auto-stops Service and disables the monitor switch; trying to enable Service while Debug is ON shows a toast and forces the switch OFF
- **Bug fixes**:
  - Bug #1: ANR fix — moved GPS wait loop from main thread to `HandlerThread`
  - Bug #2: Card status sync — reload `locationList` from DB after status change
  - Bug #3: Switch color — changed `android:thumbTint` to `app:thumbTint` for API 21 compat
  - Bug #4: Debug GPS info empty — moved UI updates to main thread via `mainHandler.post()`
- **Build convention**: Added "构建规范" section to PLAN.md — every change must produce a release APK.
- **Code logic doc**: Created `CODE_LOGIC.md` — comprehensive walkthrough of all source files and logic.
- **Bug log**: Created `bug-fix.md` — tracks all bug fixes with root cause analysis and fix details.
- Built release APK: `忘打卡-v1.3-release.apk` ✅ LATEST

---

## GPS Polling Strategy (v1.3+)

The service uses `requestLocationUpdates()` with a `LocationListener` instead of cached `getLastKnownLocation()`. This ensures fresh GPS data every cycle.

### Polling Logic

```
Service Started (右上角监控 ON, Debug OFF)
    ↓
Every 15 seconds: checkLocations()
    ├── Request GPS (always)
    ├── Calculate distance for each enabled checkpoint (always)
    ├── Update status in DB (always)
    └── Trigger reminder? → only if time window allows
        ↓
    Schedule next check in 15s (always)

Service Stopped (右上角监控 OFF)
    ↓
No GPS requests, no status updates, completely silent

Debug Mode ON (Debug模式开启)
    ↓
Service is auto-stopped if running
    ↓
MainActivity polls GPS every 15s on HandlerThread
    ├── Updates top GPS info bar on main thread
    ├── Updates card distances on main thread
    ├── Updates DB status and reloads locationList
    └── Triggers reminders on status change (time window gated)

⚠️ Debug and Service are MUTUALLY EXCLUSIVE — never run simultaneously
```

### Key Methods in `LocationMonitorService`

| Method | Purpose |
|--------|---------|
| `scheduleNextCheck()` | Re-queues the next GPS check every 15s (always) |
| `checkLocations()` | Main loop: requests GPS, calculates distances, updates status, triggers reminders (if time window allows) |

---

## Project Structure

```
anzhuo/
├── build.gradle                      # Top-level build config (AGP 8.7.2)
├── settings.gradle                   # Includes :app module
├── gradle.properties                 # AndroidX and SDK settings
├── gradlew / gradlew.bat             # Gradle wrapper scripts
├── gradle-8.9-bin.zip                # Gradle 8.9 distribution (130MB)
├── gradle-8.9/                       # Pre-extracted Gradle (mounted into container)
├── build.sh                          # Build wrapper script
├── my-release-key.jks                # Release signing key (should NOT be committed)
├── PLAN.md                           # Project planning document
├── README.md                         # Project documentation (with screenshots)
├── QWEN.md                           # AI context file (this file)
├── CODE_LOGIC.md                     # Comprehensive code logic walkthrough
├── images/                           # App screenshots for README
│   ├── 主界面.jpg
│   ├── 使用说明.jpg
│   ├── 新增打卡点.jpg
│   └── 设置.jpg
└── app/
    ├── build.gradle                  # App-level build config
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/helloworld/
        │   ├── MainActivity.java             # Main screen: RecyclerView list + service toggle switch
        │   ├── HelpActivity.java             # Usage instructions page
        │   ├── SettingsActivity.java         # Settings: vibration/popup/notification/countdown/debug
        │   ├── CheckInLocation.java          # Data model with time range logic
        │   ├── CheckInLocationAdapter.java   # RecyclerView adapter
        │   ├── CheckInLocationEntity.java    # Room entity
        │   ├── CheckInLocationDao.java       # Room DAO
        │   ├── AppDatabase.java              # Room database singleton
        │   ├── LocationMonitorService.java   # Foreground service with active GPS polling
        │   └── BootReceiver.java             # Boot completed receiver
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # Main screen with RecyclerView + FAB + SwitchCompat
            │   ├── activity_help.xml           # Usage instructions page
            │   ├── activity_settings.xml       # Settings page with 5 switches
            │   ├── item_checkin_location.xml   # Card item for location list
            │   └── dialog_add_location.xml     # Add/Edit location dialog
            └── values/
                ├── strings.xml
                ├── colors.xml
                └── themes.xml
```

## Building and Running

Builds are performed using the **`mingc/android-build-box:latest`** Docker image.

**Gradle 8.9 is pre-extracted** in `gradle-8.9/` to avoid repeated downloads.

### Using the Build Script (Recommended)

```bash
./build.sh              # Debug build (fast, no Gradle download)
./build.sh release      # Release build
./build.sh clean        # Clean + Debug build
./build.sh clean release # Clean + Release build
```

### Manual Docker Commands

```bash
# Debug APK
docker run --rm -v "$(pwd)":/project -v "$(pwd)/gradle-8.9":/opt/gradle-8.9 \
  mingc/android-build-box:latest /bin/bash -c "
    export GRADLE_HOME=/opt/gradle-8.9 && export PATH=\$GRADLE_HOME/bin:\$PATH
    cd /project && gradle assembleDebug
  "

# Release APK
docker run --rm -v "$(pwd)":/project -v "$(pwd)/gradle-8.9":/opt/gradle-8.9 \
  mingc/android-build-box:latest /bin/bash -c "
    export GRADLE_HOME=/opt/gradle-8.9 && export PATH=\$GRADLE_HOME/bin:\$PATH
    cd /project && gradle assembleRelease
  "
```

> **Note:** The `-v "$(pwd)/gradle-8.9":/opt/gradle-8.9` mount is what prevents Gradle from being re-downloaded on each build. Do not remove it.

### Output Locations

| Type | Path |
|------|------|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |

---

## Dependencies

```gradle
implementation 'androidx.recyclerview:recyclerview:1.3.2'
implementation 'androidx.cardview:cardview:1.0.0'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.room:room-runtime:2.6.1'
annotationProcessor 'androidx.room:room-compiler:2.6.1'
```

## Key Configuration Notes

- **Signing:** Release build uses `my-release-key.jks` with placeholder credentials. **Never commit this key.**
- **Minification:** ProGuard/R8 is disabled (`minifyEnabled false`).
- **Java Version:** 17 (configured in `compileOptions`)

## Development Conventions

- Uses classic Android `Activity` (not `AppCompatActivity`) where possible, but uses `AppCompatActivity` features (Toolbar, SwitchCompat) via AndroidX
- XML-based UI layouts with `RelativeLayout`, `LinearLayout`, `CardView`
- RecyclerView with custom adapter for location list
- Database operations run on `ExecutorService` (single thread), results posted to main thread via `Handler`
- Service runs as `ForegroundService` with type `location`
- Database uses `fallbackToDestructiveMigration()` for schema changes

## Permissions

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Precise GPS positioning |
| `ACCESS_COARSE_LOCATION` | Coarse positioning (fallback) |
| `ACCESS_BACKGROUND_LOCATION` | Background location monitoring (Android 10+) |
| `SYSTEM_ALERT_WINDOW` | Show reminder dialogs from service |
| `POST_NOTIFICATIONS` | System notifications (Android 13+) |
| `FOREGROUND_SERVICE` | Foreground location monitoring service |
| `FOREGROUND_SERVICE_LOCATION` | Foreground service with location type |
| `RECEIVE_BOOT_COMPLETED` | Auto-start monitoring on device boot |
| `VIBRATE` | Vibrate device on reminder |

## Important Files Reference

| File | Description |
|------|-------------|
| `app/build.gradle` | App-level build config (SDK, signing, dependencies) |
| `build.gradle` (root) | Top-level build script with AGP 8.7.2 |
| `gradle.properties` | AndroidX enabled, non-transitive R class disabled |
| `AndroidManifest.xml` | Declares activities, service, receiver, all permissions |
| `MainActivity.java` | Main screen with RecyclerView, FAB, service toggle switch, permissions |
| `HelpActivity.java` | Usage instructions page |
| `SettingsActivity.java` | Settings page (vibration/popup/notification/countdown/debug toggles) |
| `CheckInLocation.java` | Domain model: name, coords, radius, enabled, status, time ranges |
| `CheckInLocationAdapter.java` | RecyclerView adapter with switch, click & long-press |
| `CheckInLocationEntity.java` | Room entity with CRUD mapping |
| `CheckInLocationDao.java` | Room DAO with all CRUD operations |
| `AppDatabase.java` | Room database singleton (version 2, destructive migration) |
| `LocationMonitorService.java` | Foreground service with active GPS polling, geofence, smart scheduling, reminders |
| `BootReceiver.java` | BOOT_COMPLETED receiver to restart service |
| `PLAN.md` | Full development roadmap (M1-M8, v1.1-v1.3, all complete) |
| `bug-fix.md` | Bug fix log with root cause analysis |

## APK Output Naming Convention

| APK File | Description |
|----------|-------------|
| `app-debug-m1.apk` ... `m8.apk` | Milestone debug builds |
| `app-debug-v1.1.apk` | v1.1 debug build |
| `app-debug-v1.3.apk` | v1.3 debug build |
| `忘打卡-v1.3-release.apk` | **v1.3 release: Active GPS + Smart Polling + Debug Mode (LATEST)** |
| `忘打卡-v1.2-release.apk` | v1.2 release (outdated) |
