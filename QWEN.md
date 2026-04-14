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
- **UI:** `RecyclerView` + `CardView` + Material Components
- **Theme:** `Theme.MaterialComponents.Light.NoActionBar`
- **AndroidX:** Enabled
- **Database:** Room (planned for M3)

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

### M1 Completed (2026-04-13)
- Replaced old simple button UI with `RecyclerView` list
- Created `CheckInLocation` model class
- Created `CheckInLocationAdapter` for card display
- Each card shows: name, coordinates, radius, status, enable/disable switch
- Added FAB (FloatingActionButton) for adding new locations (shows placeholder toast)
- Added demo data (公司, 家) for testing list rendering
- Added Material theme with toolbar
- Built APK: `app-debug-m1.apk`

### M2 Completed (2026-04-13)
- Created `dialog_add_location.xml` with Material text inputs
- Added name, latitude, longitude, radius input fields
- Added "获取当前位置" button using `LocationManager`
- Runtime permission request for `ACCESS_FINE_LOCATION`
- Input validation (empty fields, number format, radius > 0)
- Added location permissions to `AndroidManifest.xml`
- Built APK: `app-debug-m2.apk`

### M3 Completed (2026-04-13)
- Created `CheckInLocationEntity` with Room `@Entity` annotation
- Created `CheckInLocationDao` with CRUD operations (`@Insert`, `@Update`, `@Delete`, `@Query`)
- Created `AppDatabase` singleton using Room database builder
- Replaced in-memory list with database-backed operations
- All add/toggle operations now persist to SQLite
- Removed demo data — app starts with empty list
- Built APK: `app-debug-m3.apk`

### M4 Completed (2026-04-13)
- Created `LocationMonitorService` extending `Service` with foreground notification
- Periodic GPS polling every 30 seconds using `HandlerThread`
- Haversine distance calculation via `Location.distanceBetween()`
- Tracks `inside`/`outside` status per location, triggers on status change
- Notification channel for Android 8.0+
- `BootReceiver` for auto-start on device boot
- Added permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`
- Toolbar shows service status, click to toggle start/stop
- Built APK: `app-debug-m4.apk`

### M5 Completed (2026-04-13)
- Added separate notification channels: low-priority for service status, high-priority for reminders
- Reminder notifications have sound (default notification ringtone) and vibration
- When app is in foreground: shows `AlertDialog` with "知道了" button
- When app is in background: shows detailed notification with sound, auto-cancel
- Vibration triggers on every reminder (500ms)
- Added `VIBRATE` permission
- Dialog prevents duplicate stacking via `currentDialog` reference
- Built APK: `app-debug-m5.apk`

### M6 Completed (2026-04-13)
- Added swipe-to-delete via `ItemTouchHelper` (swipe left)
- Delete confirmation dialog before removing from database
- Long-press or tap on card opens edit dialog
- Edit dialog pre-fills existing name, coordinates, radius
- Edit dialog has "删除" neutral button for quick delete
- Updated `CheckInLocationAdapter` with `onItemLongClick` callback
- Built APK: `app-debug-m6.apk`

### M7 Completed (2026-04-13)
- Added `ACCESS_BACKGROUND_LOCATION` permission to manifest
- Added `SYSTEM_ALERT_WINDOW` permission for dialog from service
- Runtime permission request flow: Fine Location → Background Location (Android 10+) → Service start
- Overlay permission request on app start for service dialogs
- BootReceiver already functional (from M4)
- Proper permission handling in `onRequestPermissionsResult`
- Built APK: `app-debug-m7.apk`

### M8 Completed (2026-04-13)
- Removed unused `AboutActivity` and its layout
- Clean build with `./gradlew clean assembleDebug`
- Built release signed APK: `忘打卡-v1.0-release.apk`
- All 8 milestones complete

### v1.1 Completed (2026-04-14)
- Added time range settings for each location:
  - **上班靠近时间范围**: enter time start/end with enable/disable switch
  - **下班远离时间范围**: leave time start/end with enable/disable switch
  - Switch OFF = always remind (no time restriction)
  - Switch ON = only remind within the specified time window
- Time range check logic in `LocationMonitorService.checkLocations()`
- Created `HelpActivity` with detailed usage instructions
- Added "📖 使用说明" button in main activity toolbar
- Database version bumped to 2 with `fallbackToDestructiveMigration()`
- Built debug APK: `app-debug-v1.1.apk`
- Built release APK: `忘打卡-v1.1-release.apk` ✅ LATEST

### v1.2 Completed (2026-04-14)
- Created `SettingsActivity` with 4 toggle switches:
  - 📳 震动提醒 (default: ON)
  - 💬 弹窗提醒 (default: ON) — controls AlertDialog when app is foreground
  - 🔔 通知提醒 (default: ON) — controls notification when app is background
  - ⏱️ 倒计时提醒 (default: ON, **10s configurable**) — user-editable seconds input
- Settings persisted via `SharedPreferences` (`checkin_settings`)
- Static helper methods in `SettingsActivity` for Service to read settings
- `LocationMonitorService.triggerReminder()` checks all 4 settings before triggering
- Countdown starts **once** when reminder is triggered (not continuously)
- Countdown duration is configurable (1–300 seconds, default 10)
- Countdown finishes with final vibration + "请立即打卡！" notification
- Added "⚙ 设置" button to main activity toolbar (right side)
- Cancel countdown timer on dialog dismiss and service destroy
- `build.sh` auto-downloads Gradle 8.9 if zip is missing
- Built release APK: `忘打卡-v1.2-release.apk` ✅ LATEST

---

## New Requirements (v1.1)

### 需求清单

| # | 需求 | 说明 |
|---|------|------|
| 1 | **上班靠近时间范围** | 用户可设置时间段（如 08:50 ~ 09:03），仅在该时间段内进入区域时提醒 |
| 2 | **下班远离时间范围** | 用户可设置时间段（如 17:50 ~ 18:10），仅在该时间段内离开区域时提醒 |
| 3 | **时间范围开关** | 每个时间范围有独立开关：打开=遵从时间范围，关闭=任何时间都提醒 |
| 4 | **防止误报警** | 通过时间范围限制，避免非上下班时段进出误报警 |
| 5 | **使用说明页面** | 主界面顶部增加"使用说明"按钮，跳转查看详细使用帮助 |

### 功能清单

| # | 功能 | 技术实现 |
|---|------|----------|
| 1.1 | 时间范围输入 | 对话框内增加 4 个时间输入框 + 2 个开关 |
| 1.2 | 时间范围存储 | Entity 新增 6 个字段，数据库版本升级到 2 |
| 1.3 | 时间范围判断 | `isInEnterTimeWindow()` / `isInLeaveTimeWindow()` 方法 |
| 1.4 | 地理围栏时间过滤 | Service 中判断时间窗口，不在范围内则 `continue` |
| 2.1 | 使用说明页面 | `HelpActivity` + `activity_help.xml` |
| 2.2 | 入口按钮 | Toolbar 左侧增加 "📖 使用说明" 按钮 |

## New Requirements (v1.2)

### 需求清单

| # | 需求 | 说明 |
|---|------|------|
| 1 | **震动提醒** | 提醒时手机震动，默认开启 |
| 2 | **弹窗提醒开关** | 控制是否在前台时显示 AlertDialog 弹窗，默认开启 |
| 3 | **通知提醒开关** | 控制是否在后台时发送系统通知，默认开启 |
| 4 | **10秒倒计时** | 触发提醒后启动手机自带倒计时（10秒），默认开启 |
| 5 | **设置页面** | 主界面工具栏增加设置按钮，跳转设置页面管理所有开关 |

### 功能清单

| # | 功能 | 技术实现 |
|---|------|----------|
| 1.1 | 设置页面 UI | `SettingsActivity` + `activity_settings.xml`，4 个 SwitchCompat |
| 1.2 | 设置持久化 | `SharedPreferences` 存储 4 个布尔值 |
| 1.3 | 震动提醒 | 已有 `vibrate()` 方法，默认 true |
| 1.4 | 弹窗/通知开关 | Service 读取设置后决定是否触发 |
| 1.5 | 倒计时提醒 | 使用 `CountDownTimer` 10秒倒计时 + 铃声提醒 |
| 1.6 | 设置入口 | Toolbar 右侧增加 "⚙ 设置" 按钮 |

## Final APK Files

| File | Description |
|------|-------------|
| `app-debug-m1.apk` | List UI only (demo data) |
| `app-debug-m2.apk` | + Add location with GPS |
| `app-debug-m3.apk` | + Room database persistence |
| `app-debug-m4.apk` | + Foreground service monitoring |
| `app-debug-m5.apk` | + Reminder notifications (dialog + sound + vibration) |
| `app-debug-m6.apk` | + Edit, delete, swipe-to-delete |
| `app-debug-m7.apk` | + Full permission handling (background location, overlay) |
| `app-debug-m8.apk` | Final debug build (clean) |
| `忘打卡-v1.0-release.apk` | **Release signed APK** |

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
├── README.md                         # Project documentation
├── QWEN.md                           # AI context file (this file)
├── 忘打卡-v1.0-release.apk           # Release APK
├── app-debug-v1.1.apk                # v1.1 debug build
├── app-debug-m1.apk ... m8.apk       # Debug APK versions
└── app/
    ├── build.gradle                  # App-level build config
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/helloworld/
        │   ├── MainActivity.java           # Main screen with RecyclerView
        │   ├── HelpActivity.java           # Usage instructions page
        │   ├── SettingsActivity.java       # Settings page (vibration/popup/notification/countdown)
        │   ├── CheckInLocation.java        # Data model
        │   ├── CheckInLocationAdapter.java # RecyclerView adapter
        │   ├── CheckInLocationEntity.java  # Room entity
        │   ├── CheckInLocationDao.java     # Room DAO
        │   ├── AppDatabase.java            # Room database singleton
        │   ├── LocationMonitorService.java # Foreground service
        │   └── BootReceiver.java           # Boot completed receiver
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # Main screen with RecyclerView + FAB
            │   ├── activity_help.xml           # Usage instructions page
            │   ├── activity_settings.xml       # Settings page with 4 switches
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

### First-Time Setup (only needed once)

```bash
# 1. Download Gradle 8.9
docker run --rm -v "$(pwd)":/project mingc/android-build-box:latest \
  /bin/bash -c "curl -fsSL -o /project/gradle-8.9-bin.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip"

# 2. Extract
unzip -qo gradle-8.9-bin.zip

# 3. Verify
ls gradle-8.9/bin/gradle   # should exist
```

### Using the Build Script (Recommended)

```bash
./build.sh              # Debug build (fast, no Gradle download)
./build.sh release      # Release build
./build.sh clean        # Clean + Debug build
./build.sh clean release # Clean + Release build
```

**IMPORTANT: After every feature update, always build a release APK:**
```bash
./build.sh release
cp app/build/outputs/apk/release/app-release.apk 忘打卡-v{VERSION}-release.apk
```
- Replace `{VERSION}` with the current version number (e.g., `1.1`, `1.2`)
- The release APK is signed with `my-release-key.jks`
- Always copy it to the project root with a versioned name so the user can install the latest version

Output locations:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

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

# Clean
docker run --rm -v "$(pwd)":/project -v "$(pwd)/gradle-8.9":/opt/gradle-8.9 \
  mingc/android-build-box:latest /bin/bash -c "
    export GRADLE_HOME=/opt/gradle-8.9 && export PATH=\$GRADLE_HOME/bin:\$PATH
    cd /project && gradle clean
  "
```

> **Note:** The `-v "$(pwd)/gradle-8.9":/opt/gradle-8.9` mount is what prevents Gradle from being re-downloaded on each build. Do not remove it.

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
- Placeholder toasts for not-yet-implemented features
- Demo data loaded in `onCreate` for UI testing until M3 (persistence) is done

## Important Files

| File | Description |
|------|-------------|
| `app/build.gradle` | App-level build config (SDK, signing, dependencies) |
| `build.gradle` (root) | Top-level build script with AGP 8.7.2 |
| `gradle.properties` | AndroidX enabled, non-transitive R class disabled |
| `AndroidManifest.xml` | Declares activities, service, receiver, all permissions |
| `MainActivity.java` | Main screen with RecyclerView, FAB, service toggle, permissions |
| `HelpActivity.java` | Usage instructions page |
| `CheckInLocation.java` | Domain model: name, coords, radius, enabled, status, time ranges |
| `CheckInLocationAdapter.java` | RecyclerView adapter with switch, click & long-press |
| `CheckInLocationEntity.java` | Room entity with CRUD mapping |
| `CheckInLocationDao.java` | Room DAO with all CRUD operations |
| `AppDatabase.java` | Room database singleton |
| `LocationMonitorService.java` | Foreground service with GPS polling, geofence, reminders |
| `BootReceiver.java` | BOOT_COMPLETED receiver to restart service |
| `PLAN.md` | Full development roadmap (M1-M8, all complete) |

## APK Output Naming Convention

| APK File | Description |
|----------|-------------|
| `app-debug-m1.apk` | M1: List UI with demo data |
| `app-debug-m2.apk` | M2: Add location with GPS |
| `app-debug-m3.apk` | M3: Room database persistence |
| `app-debug-m4.apk` | M4: Foreground service monitoring |
| `app-debug-m5.apk` | M5: Reminder notifications (dialog + sound + vibration) |
| `app-debug-m6.apk` | M6: Edit, delete, swipe-to-delete |
| `app-debug-m7.apk` | M7: Full permission handling |
| `app-debug-m8.apk` | M8: Final clean debug build |
| `app-debug-v1.1.apk` | v1.1 debug build |
| `忘打卡-v1.2-release.apk` | **v1.2 release: Settings + Countdown (LATEST)** |
| `忘打卡-v1.1-release.apk` | v1.1 release (outdated) |
| `忘打卡-v1.0-release.apk` | v1.0 release (outdated) |
