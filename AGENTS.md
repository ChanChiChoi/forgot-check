# AGENTS.md

## Scope
- This repo is a single-module Android app. `settings.gradle` only includes `:app`.
- Main code lives under `app/src/main/java/com/example/helloworld/`.

## Build And Verify
- Use `./build.sh` for builds. It wraps Docker and mounts the repo-local `gradle-8.9/` directory into `mingc/android-build-box:latest` so Gradle is not re-downloaded each time.
- Common commands:
  - `./build.sh` -> debug build
  - `./build.sh release` -> release build
  - `./build.sh clean` -> clean + debug build
  - `./build.sh clean release` -> clean + release build
- There is no CI, no test suite, no lint config, and no formatter config in the repo. The practical verification step is building the APK.
- `PLAN.md` says every feature change should end with a release APK build and doc sync. Keep that convention unless the user says otherwise.

## App Wiring
- Launch entrypoint is `MainActivity`; background monitoring runs in `LocationMonitorService`; reboot restart is `BootReceiver`.
- Persistence is Room only: `AppDatabase` + `CheckInLocationDao` + `CheckInLocationEntity`.
- `AppDatabase` uses `fallbackToDestructiveMigration()`. Any schema version change will wipe local data unless migrations are added.

## Behavior Quirks Worth Preserving
- Service polling interval is hard-coded to 15s in `LocationMonitorService`.
- Current service behavior is: always poll/update status every 15s while running, and only gate the reminder trigger by the time windows. Older prose docs describe a smarter polling strategy; trust the code.
- Debug mode is stored in `SharedPreferences` (`checkin_settings`) and is mutually exclusive with the foreground service. `MainActivity` forces the service off while Debug is enabled.
- Time-window switches mean "restrict reminders to this window when ON" and "always allow reminders when OFF". That inverted meaning is implemented in `CheckInLocation.isInEnterTimeWindow()` / `isInLeaveTimeWindow()`.
- Initial geofence status `unknown` does not trigger reminders; reminders only fire on later status transitions.
- Settings are persisted in `SharedPreferences`, not Room. Countdown default is 10s and valid input is 1-300 seconds.

## Permissions And Runtime Caveats
- The app expects overlay permission for service dialogs (`SYSTEM_ALERT_WINDOW`) and requests it from `MainActivity`.
- `LocationMonitorService` takes a partial `WakeLock` during GPS polling. Be careful changing service shutdown/error paths; it must always be released.

## Files Agents Should Update Deliberately
- Project docs tracked in git and treated as part of the workflow: `README.md`, `PLAN.md`, `QWEN.md`, `CODE_LOGIC.md`, `bug-fix.md`.
- `README.md` and `QWEN.md` contain useful context, but some behavior notes are stale. Prefer `app/` source, Gradle files, and `build.sh` when they disagree.

## Sensitive / Generated Artifacts
- `app/build/`, `build/`, `gradle-8.9/`, `gradle-8.9-bin.zip`, `*.apk`, and `my-release-key.jks` are ignored by `.gitignore`.
- `app/build.gradle` references `../my-release-key.jks` with inline credentials for release signing. Do not move that config into committed docs or new files, and avoid touching signing config unless the task requires it.
