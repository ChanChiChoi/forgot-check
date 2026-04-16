package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements CheckInLocationAdapter.OnLocationActionListener {

    private static final int SERVICE_LOCATION_PERMISSION_REQUEST_CODE = 1000;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 1003;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1004;

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvMonitorStatus;
    private androidx.appcompat.widget.SwitchCompat switchServiceStatus;
    private FloatingActionButton fabAdd;
    private CheckInLocationAdapter adapter;
    private List<CheckInLocation> locationList = new ArrayList<>();

    // Debug mode fields
    private android.widget.LinearLayout layoutPermissionWarning;
    private android.widget.LinearLayout layoutDebug;
    private TextView tvPermissionWarning;
    private TextView tvDebugGPS;
    private TextView tvDebugTime;
    private Handler debugHandler;
    private HandlerThread debugHandlerThread;
    private Handler debugBackgroundHandler;
    private android.location.LocationManager debugLocationManager;
    private android.location.LocationListener debugLocationListener;
    private volatile android.location.Location debugCurrentLocation;
    private Runnable debugLocationRunnable;
    private boolean pendingStartServiceAfterPermission;
    private boolean ignoreServiceSwitchCallback;

    // Periodic refresh
    private Handler refreshHandler;
    private Runnable refreshRunnable;

    // Database
    private AppDatabase database;
    private ExecutorService executorService;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Add dialog fields
    private AlertDialog addDialog;
    private TextInputEditText etName, etLatitude, etLongitude, etRadius;
    private TextInputEditText etEnterStart, etEnterEnd, etLeaveStart, etLeaveEnd;
    private androidx.appcompat.widget.SwitchCompat switchEnterTime, switchLeaveTime;
    private TextView tvLocationStatus, tvDialogTitle;
    private Button btnGetLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvMonitorStatus = findViewById(R.id.tvMonitorStatus);
        switchServiceStatus = findViewById(R.id.switchServiceStatus);
        fabAdd = findViewById(R.id.fabAdd);

        // Debug views
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning);
        layoutDebug = findViewById(R.id.layoutDebug);
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning);
        tvDebugGPS = findViewById(R.id.tvDebugGPS);
        tvDebugTime = findViewById(R.id.tvDebugTime);
        Button btnPermissionWarningAction = findViewById(R.id.btnPermissionWarningAction);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CheckInLocationAdapter(this);
        recyclerView.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showAddLocationDialog());
        btnPermissionWarningAction.setOnClickListener(v -> openAppSettings());

        // Service status switch click to toggle
        switchServiceStatus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (ignoreServiceSwitchCallback) {
                return;
            }

            // Prevent toggling service while debug mode is active
            if (SettingsActivity.isDebugEnabled(MainActivity.this)) {
                Toast.makeText(this, "Debug模式下请先关闭Debug再开启监控", Toast.LENGTH_SHORT).show();
                setServiceSwitchChecked(false);
                return;
            }
            if (isChecked) {
                startMonitoringServiceWithPermissionCheck();
            } else {
                pendingStartServiceAfterPermission = false;
                LocationMonitorService.stopService(this);
                LocationMonitorService.isServiceRunning = false;
                Toast.makeText(this, "位置监控已停止", Toast.LENGTH_SHORT).show();
            }
        });

        // Toolbar click to toggle service
        findViewById(R.id.toolbar).setOnClickListener(v -> toggleLocationService());

        // Help button
        Button btnHelp = findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HelpActivity.class));
        });

        // Settings button
        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        // Swipe to delete
        setupSwipeToDelete();

        updateEmptyView();
        updateServiceStatus();
        requestPermissions();
        updateMonitorStatusText();

        // Initialize database
        database = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        // Load data from database
        loadLocationsFromDatabase();

        // Initialize debug mode
        debugHandler = new Handler(Looper.getMainLooper());
        refreshHandler = new Handler(Looper.getMainLooper());
        debugLocationManager = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        debugLocationListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location location) {
                debugCurrentLocation = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        // Background thread for GPS requests (avoid ANR on main thread)
        debugHandlerThread = new HandlerThread("DebugLocationThread");
        debugHandlerThread.start();
        debugBackgroundHandler = new Handler(debugHandlerThread.getLooper());

        debugLocationRunnable = new Runnable() {
            @Override
            public void run() {
                requestDebugLocation();
                if (SettingsActivity.isDebugEnabled(MainActivity.this)) {
                    debugBackgroundHandler.postDelayed(this, 15_000); // Every 15 seconds
                }
            }
        };

        updateDebugModeVisibility();

        // Start periodic refresh
        startPeriodicRefresh();
    }

    private void startPeriodicRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateServiceStatus();
                updatePermissionWarning();
                refreshHandler.postDelayed(this, 10_000);
            }
        };
        refreshHandler.post(refreshRunnable);
    }

    private void stopPeriodicRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateDebugModeVisibility();
        updatePermissionWarning();
        updateMonitorStatusText();
        startPeriodicRefresh();

        if (pendingStartServiceAfterPermission && hasRequiredMonitoringPermissions()) {
            pendingStartServiceAfterPermission = false;
            startMonitoringService();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop debug location updates when activity is paused
        stopDebugLocation();
        stopPeriodicRefresh();
    }

    private void requestPermissions() {
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }

        // Request background location permission for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                // We'll request this after fine location is granted
                // For now, just note it
            }
        }

        // Request overlay permission for dialog from service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "需要悬浮窗权限以显示打卡提醒",
                        Toast.LENGTH_LONG).show();
                Intent overlayIntent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivityForResult(overlayIntent, OVERLAY_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "未授予悬浮窗权限，后台提醒可能无法显示弹窗",
                            Toast.LENGTH_LONG).show();
                }
                updateMonitorStatusText();
            }
        }
    }

    private void toggleLocationService() {
        switchServiceStatus.setChecked(!switchServiceStatus.isChecked());
    }

    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean hasFineLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasRequiredMonitoringPermissions() {
        return hasFineLocationPermission() && hasBackgroundLocationPermission();
    }

    private void startMonitoringServiceWithPermissionCheck() {
        pendingStartServiceAfterPermission = true;

        if (!hasFineLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    SERVICE_LOCATION_PERMISSION_REQUEST_CODE);
            setServiceSwitchChecked(false);
            return;
        }

        if (!hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermission();
            setServiceSwitchChecked(false);
            updatePermissionWarning();
            return;
        }

        pendingStartServiceAfterPermission = false;
        startMonitoringService();
    }

    private void startMonitoringService() {
        LocationMonitorService.startService(this);
        LocationMonitorService.isServiceRunning = true;
        setServiceSwitchChecked(true);
        updateMonitorStatusText();
        Toast.makeText(this, "位置监控已启动", Toast.LENGTH_LONG).show();
    }

    private void requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this,
                    "Android 11+ 需要在系统设置里把位置权限改成“始终允许”，后台监控才能工作",
                    Toast.LENGTH_LONG).show();
            openAppSettings();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    BACKGROUND_LOCATION_REQUEST_CODE);
        }
    }

    private void updateServiceStatus() {
        boolean running = LocationMonitorService.isServiceRunning;
        setServiceSwitchChecked(running);
        updateMonitorStatusText();
    }

    private void updatePermissionWarning() {
        if (layoutPermissionWarning == null || tvPermissionWarning == null) {
            return;
        }

        boolean missingBackgroundPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !hasBackgroundLocationPermission();

        if (!missingBackgroundPermission) {
            layoutPermissionWarning.setVisibility(View.GONE);
            updateMonitorStatusText();
            return;
        }

        layoutPermissionWarning.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tvPermissionWarning.setText("后台监控需要在系统设置中将位置权限改为“始终允许”");
        } else {
            tvPermissionWarning.setText("后台监控需要允许后台位置权限，否则退到后台后不会轮询");
        }
        updateMonitorStatusText();
    }

    private void updateMonitorStatusText() {
        if (tvMonitorStatus == null) {
            return;
        }

        String status;
        if (SettingsActivity.isDebugEnabled(this)) {
            status = "监控状态：Debug 模式运行中，后台监控服务已关闭";
        } else if (!hasFineLocationPermission()) {
            status = "监控状态：缺少前台位置权限，无法开启监控";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            status = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? "监控状态：缺少“始终允许”位置权限，退到后台后不会轮询"
                    : "监控状态：缺少后台位置权限，退到后台后不会轮询";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            status = "监控状态：监控可运行，但缺少悬浮窗权限，后台只能发通知不能弹窗";
        } else if (LocationMonitorService.isServiceRunning) {
            status = "监控状态：运行中，每 15 秒轮询并更新打卡点状态";
        } else {
            status = "监控状态：未启动";
        }

        tvMonitorStatus.setText(status);
    }

    private void setServiceSwitchChecked(boolean checked) {
        ignoreServiceSwitchCallback = true;
        switchServiceStatus.setChecked(checked);
        ignoreServiceSwitchCallback = false;
    }

    /**
     * Show/hide debug info panel based on settings.
     */
    private void updateDebugModeVisibility() {
        boolean debugEnabled = SettingsActivity.isDebugEnabled(this);
        layoutDebug.setVisibility(debugEnabled ? View.VISIBLE : View.GONE);

        if (debugEnabled) {
            // Debug mode takes over: stop the service to avoid GPS competition
            if (LocationMonitorService.isServiceRunning) {
                LocationMonitorService.stopService(this);
                LocationMonitorService.isServiceRunning = false;
            }
            setServiceSwitchChecked(false);
            startDebugLocation();
        } else {
            stopDebugLocation();
            tvDebugGPS.setText("GPS: 未获取");
            tvDebugTime.setText("更新时间: --");
            debugCurrentLocation = null;
            adapter.setCurrentLocation(null);
        }
    }

    private void startDebugLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            tvDebugGPS.setText("GPS: 无位置权限");
            return;
        }

        // Recreate thread if it was quit (from onPause)
        if (debugBackgroundHandler == null || !debugHandlerThread.isAlive()) {
            debugHandlerThread = new HandlerThread("DebugLocationThread");
            debugHandlerThread.start();
            debugBackgroundHandler = new Handler(debugHandlerThread.getLooper());
        }

        debugBackgroundHandler.removeCallbacks(debugLocationRunnable);
        debugBackgroundHandler.post(debugLocationRunnable);
    }

    private void stopDebugLocation() {
        debugBackgroundHandler.removeCallbacks(debugLocationRunnable);
        if (debugHandlerThread != null) {
            debugHandlerThread.quit();
        }
        if (debugLocationManager != null && debugLocationListener != null) {
            try {
                debugLocationManager.removeUpdates(debugLocationListener);
            } catch (Exception e) {
                // Ignore
            }
        }
        debugCurrentLocation = null;
        if (debugCountdownTimer != null) {
            debugCountdownTimer.cancel();
        }
    }

    private void requestDebugLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            debugLocationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER, 0, 0, debugLocationListener);

            // Wait briefly for GPS fix (up to 8 seconds)
            long startTime = System.currentTimeMillis();
            while (debugCurrentLocation == null && (System.currentTimeMillis() - startTime) < 8_000) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }

            debugLocationManager.removeUpdates(debugLocationListener);

            if (debugCurrentLocation != null) {
                final android.location.Location loc = debugCurrentLocation;
                mainHandler.post(() -> {
                    updateDebugUI();
                    adapter.setCurrentLocation(loc);
                });
                checkDebugGeofence();
            } else {
                // Fallback to network location
                android.location.Location netLoc = debugLocationManager.getLastKnownLocation(
                        android.location.LocationManager.NETWORK_PROVIDER);
                if (netLoc != null) {
                    debugCurrentLocation = netLoc;
                    final android.location.Location loc2 = netLoc;
                    mainHandler.post(() -> {
                        updateDebugUI();
                        adapter.setCurrentLocation(loc2);
                    });
                    checkDebugGeofence();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * In debug mode: check distance to each enabled checkpoint,
     * trigger reminder on status change (same logic as LocationMonitorService).
     */
    private void checkDebugGeofence() {
        if (debugCurrentLocation == null) return;

        executorService.execute(() -> {
            List<CheckInLocationEntity> entities = database.locationDao().getAllLocations();
            boolean statusChanged = false;

            for (CheckInLocationEntity entity : entities) {
                if (!entity.enabled) continue;

                CheckInLocation location = entity.toCheckInLocation();

                float[] results = new float[1];
                android.location.Location.distanceBetween(
                        debugCurrentLocation.getLatitude(),
                        debugCurrentLocation.getLongitude(),
                        entity.latitude,
                        entity.longitude,
                        results
                );
                float distance = results[0];

                boolean isInside = distance <= entity.radiusMeters;
                String newStatus = isInside ? "inside" : "outside";

                // Always update status in database
                if (!entity.status.equals(newStatus)) {
                    String oldStatus = entity.status;

                    // Update status in database
                    database.locationDao().updateStatus(entity.id, newStatus);
                    statusChanged = true;

                    if (!"unknown".equals(oldStatus)) {
                        boolean timeWindowAllows = isInside
                                ? location.isInEnterTimeWindow()
                                : location.isInLeaveTimeWindow();

                        if (timeWindowAllows) {
                            String reminderMessage = isInside
                                    ? "你进入了 " + entity.name + "，请记得打卡！"
                                    : "你离开了 " + entity.name + "，请记得打卡！";

                            // Trigger reminder on main thread
                            final String msg = reminderMessage;
                            mainHandler.post(() -> triggerDebugReminder(msg));
                        }
                    }
                }
            }

            // Reload location list from DB so cards show updated status
            if (statusChanged) {
                mainHandler.post(() -> loadLocationsFromDatabase());
            }
        });
    }

    /**
     * Trigger reminder respecting settings (vibration, popup, notification, countdown).
     * Used by debug mode.
     */
    private void triggerDebugReminder(String message) {
        boolean vibrateEnabled = SettingsActivity.isVibrationEnabled(this);
        boolean popupEnabled = SettingsActivity.isPopupEnabled(this);
        boolean notificationEnabled = SettingsActivity.isNotificationEnabled(this);
        boolean countdownEnabled = SettingsActivity.isCountdownEnabled(this);

        // Always show popup in debug mode since we're in foreground
        if (popupEnabled) {
            showDebugReminderDialog(message, countdownEnabled);
        }

        if (vibrateEnabled) {
            debugVibrate();
        }

        if (countdownEnabled) {
            startDebugCountdown(message);
        }
    }

    private void showDebugReminderDialog(String message, boolean showCountdown) {
        String dialogMessage = message;
        if (showCountdown) {
            dialogMessage = message + "\n\n10秒倒计时提醒即将开始...";
        }

        new AlertDialog.Builder(this)
                .setTitle("⏰ 打卡提醒 [Debug]")
                .setMessage(dialogMessage)
                .setPositiveButton("知道了", (dialog, which) -> {
                    // Do NOT cancel countdown timer — let it run to trigger follow-up reminder
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void debugVibrate() {
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(2000, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(2000);
            }
        }
    }

    private android.os.CountDownTimer debugCountdownTimer;

    private void startDebugCountdown(String message) {
        if (debugCountdownTimer != null) {
            debugCountdownTimer.cancel();
        }

        final long countdownMillis = SettingsActivity.getCountdownMillis(this);

        // Play alarm sound only if enabled AND system is not silent/vibrate
        if (SettingsActivity.shouldPlayAlarmSound(this)) {
            try {
                Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                android.media.Ringtone ringtone = RingtoneManager.getRingtone(this, alarmSound);
                if (ringtone != null) {
                    ringtone.play();
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        debugCountdownTimer = new android.os.CountDownTimer(countdownMillis, countdownMillis) {
            @Override
            public void onTick(long millisUntilFinished) {}

            @Override
            public void onFinish() {
                debugVibrate();
                showDebugReminderDialog(message + " ⏰ 请立即打卡！", false);
            }
        }.start();
    }

    private void updateDebugUI() {
        if (debugCurrentLocation == null) return;

        String timeStr = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());

        tvDebugGPS.setText(String.format("GPS: %.6f, %.6f",
                debugCurrentLocation.getLatitude(),
                debugCurrentLocation.getLongitude()));
        tvDebugTime.setText("更新时间: " + timeStr);
    }

    private void loadLocationsFromDatabase() {
        executorService.execute(() -> {
            List<CheckInLocationEntity> entities = database.locationDao().getAllLocations();
            List<CheckInLocation> locations = new ArrayList<>();
            for (CheckInLocationEntity entity : entities) {
                locations.add(entity.toCheckInLocation());
            }
            mainHandler.post(() -> {
                locationList.clear();
                locationList.addAll(locations);
                adapter.setLocations(locationList);
                updateEmptyView();
            });
        });
    }

    private void updateEmptyView() {
        tvEmpty.setVisibility(locationList.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(locationList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ==================== M2: Add Location Dialog ====================

    private void showAddLocationDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_location, null);

        etName = dialogView.findViewById(R.id.etName);
        etLatitude = dialogView.findViewById(R.id.etLatitude);
        etLongitude = dialogView.findViewById(R.id.etLongitude);
        etRadius = dialogView.findViewById(R.id.etRadius);
        tvLocationStatus = dialogView.findViewById(R.id.tvLocationStatus);
        tvDialogTitle = dialogView.findViewById(R.id.tvDialogTitle);
        btnGetLocation = dialogView.findViewById(R.id.btnGetLocation);

        etEnterStart = dialogView.findViewById(R.id.etEnterStart);
        etEnterEnd = dialogView.findViewById(R.id.etEnterEnd);
        etLeaveStart = dialogView.findViewById(R.id.etLeaveStart);
        etLeaveEnd = dialogView.findViewById(R.id.etLeaveEnd);
        switchEnterTime = dialogView.findViewById(R.id.switchEnterTime);
        switchLeaveTime = dialogView.findViewById(R.id.switchLeaveTime);

        // Default: time range switches OFF (meaning always remind, no time restriction)
        switchEnterTime.setChecked(false);
        switchLeaveTime.setChecked(false);

        tvDialogTitle.setText("添加打卡地点");
        btnGetLocation.setOnClickListener(v -> requestLocationAndFill());

        addDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        addDialog.setOnShowListener(dialog -> {
            Button saveButton = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> saveNewLocation());
        });

        addDialog.show();
    }

    private void requestLocationAndFill() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }
        getCurrentLocation();
    }

    private void getCurrentLocation() {
        tvLocationStatus.setText("正在获取位置...");
        btnGetLocation.setEnabled(false);

        android.location.LocationManager locationManager =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);

        try {
            Location location = locationManager.getLastKnownLocation(
                    android.location.LocationManager.GPS_PROVIDER);

            if (location == null) {
                location = locationManager.getLastKnownLocation(
                        android.location.LocationManager.NETWORK_PROVIDER);
            }

            if (location != null) {
                etLatitude.setText(String.valueOf(location.getLatitude()));
                etLongitude.setText(String.valueOf(location.getLongitude()));
                tvLocationStatus.setText(String.format("已获取: %.6f, %.6f",
                        location.getLatitude(), location.getLongitude()));
            } else {
                tvLocationStatus.setText("无法获取当前位置，请手动输入经纬度");
            }
        } catch (SecurityException e) {
            tvLocationStatus.setText("位置权限被拒绝");
        }

        btnGetLocation.setEnabled(true);
    }

    private void saveNewLocation() {
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String latStr = etLatitude.getText() != null ? etLatitude.getText().toString().trim() : "";
        String lonStr = etLongitude.getText() != null ? etLongitude.getText().toString().trim() : "";
        String radiusStr = etRadius.getText() != null ? etRadius.getText().toString().trim() : "200";

        // Time range values
        boolean enterEnabled = switchEnterTime.isChecked();
        String enterStart = etEnterStart.getText() != null ? etEnterStart.getText().toString().trim() : "08:50";
        String enterEnd = etEnterEnd.getText() != null ? etEnterEnd.getText().toString().trim() : "09:03";
        boolean leaveEnabled = switchLeaveTime.isChecked();
        String leaveStart = etLeaveStart.getText() != null ? etLeaveStart.getText().toString().trim() : "17:50";
        String leaveEnd = etLeaveEnd.getText() != null ? etLeaveEnd.getText().toString().trim() : "18:10";

        if (name.isEmpty()) {
            Toast.makeText(this, "请输入地点名称", Toast.LENGTH_SHORT).show();
            return;
        }

        if (latStr.isEmpty() || lonStr.isEmpty()) {
            Toast.makeText(this, "请输入经纬度或点击\"获取当前位置\"", Toast.LENGTH_SHORT).show();
            return;
        }

        double latitude, longitude;
        int radius;
        try {
            latitude = Double.parseDouble(latStr);
            longitude = Double.parseDouble(lonStr);
            radius = Integer.parseInt(radiusStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "经纬度或半径格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }

        if (radius <= 0) {
            Toast.makeText(this, "半径必须大于0", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to database
        final CheckInLocationEntity entity = new CheckInLocationEntity(name, latitude, longitude, radius);
        entity.enterTimeEnabled = enterEnabled;
        entity.enterTimeStart = enterStart;
        entity.enterTimeEnd = enterEnd;
        entity.leaveTimeEnabled = leaveEnabled;
        entity.leaveTimeStart = leaveStart;
        entity.leaveTimeEnd = leaveEnd;
        executorService.execute(() -> {
            long newId = database.locationDao().insertLocation(entity);
            entity.id = newId;

            mainHandler.post(() -> {
                CheckInLocation newLocation = entity.toCheckInLocation();
                locationList.add(0, newLocation);
                adapter.setLocations(locationList);
                updateEmptyView();

                Toast.makeText(this, "已保存: " + name, Toast.LENGTH_SHORT).show();

                if (addDialog != null) {
                    addDialog.dismiss();
                    addDialog = null;
                }
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SERVICE_LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMonitoringServiceWithPermissionCheck();
            } else {
                pendingStartServiceAfterPermission = false;
                Toast.makeText(this, "需要位置权限才能开启监控", Toast.LENGTH_SHORT).show();
                updatePermissionWarning();
                updateMonitorStatusText();
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, "需要位置权限才能获取当前位置", Toast.LENGTH_SHORT).show();
                tvLocationStatus.setText("权限被拒绝，请手动输入经纬度");
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingStartServiceAfterPermission = false;
                startMonitoringService();
                updatePermissionWarning();
                updateMonitorStatusText();
            } else {
                pendingStartServiceAfterPermission = false;
                Toast.makeText(this, "后台位置权限被拒绝，监控服务可能无法正常工作",
                        Toast.LENGTH_LONG).show();
                updatePermissionWarning();
                updateMonitorStatusText();
            }
        }
    }

    // ==================== M6: Swipe to Delete ====================

    private void setupSwipeToDelete() {
        ItemTouchHelper.SimpleCallback swipeCallback =
                new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                    @Override
                    public boolean onMove(@NonNull RecyclerView recyclerView,
                                          @NonNull RecyclerView.ViewHolder viewHolder,
                                          @NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                        int position = viewHolder.getAdapterPosition();
                        if (position >= 0 && position < locationList.size()) {
                            CheckInLocation location = locationList.get(position);
                            confirmDeleteLocation(location, position);
                        }
                    }
                };

        new ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);
    }

    private void confirmDeleteLocation(final CheckInLocation location, final int position) {
        new AlertDialog.Builder(this)
                .setTitle("删除打卡地点")
                .setMessage("确定要删除 \"" + location.getName() + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    executorService.execute(() -> {
                        CheckInLocationEntity entity = database.locationDao()
                                .getLocationById(location.getId());
                        if (entity != null) {
                            database.locationDao().deleteLocation(entity);
                        }
                        mainHandler.post(() -> {
                            locationList.remove(position);
                            adapter.setLocations(locationList);
                            updateEmptyView();
                            Toast.makeText(this, "已删除: " + location.getName(),
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    adapter.setLocations(locationList); // Reset the swipe
                })
                .show();
    }

    // ==================== M6: Edit Location ====================

    private void showEditLocationDialog(final CheckInLocation location) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_location, null);

        etName = dialogView.findViewById(R.id.etName);
        etLatitude = dialogView.findViewById(R.id.etLatitude);
        etLongitude = dialogView.findViewById(R.id.etLongitude);
        etRadius = dialogView.findViewById(R.id.etRadius);
        tvLocationStatus = dialogView.findViewById(R.id.tvLocationStatus);
        tvDialogTitle = dialogView.findViewById(R.id.tvDialogTitle);
        btnGetLocation = dialogView.findViewById(R.id.btnGetLocation);

        etEnterStart = dialogView.findViewById(R.id.etEnterStart);
        etEnterEnd = dialogView.findViewById(R.id.etEnterEnd);
        etLeaveStart = dialogView.findViewById(R.id.etLeaveStart);
        etLeaveEnd = dialogView.findViewById(R.id.etLeaveEnd);
        switchEnterTime = dialogView.findViewById(R.id.switchEnterTime);
        switchLeaveTime = dialogView.findViewById(R.id.switchLeaveTime);

        // Pre-fill with existing values
        etName.setText(location.getName());
        etLatitude.setText(String.valueOf(location.getLatitude()));
        etLongitude.setText(String.valueOf(location.getLongitude()));
        etRadius.setText(String.valueOf(location.getRadiusMeters()));
        tvDialogTitle.setText("编辑打卡地点");
        tvLocationStatus.setText("编辑模式");

        // Pre-fill time range values
        switchEnterTime.setChecked(location.isEnterTimeEnabled());
        etEnterStart.setText(location.getEnterTimeStart() != null ? location.getEnterTimeStart() : "08:50");
        etEnterEnd.setText(location.getEnterTimeEnd() != null ? location.getEnterTimeEnd() : "09:03");
        switchLeaveTime.setChecked(location.isLeaveTimeEnabled());
        etLeaveStart.setText(location.getLeaveTimeStart() != null ? location.getLeaveTimeStart() : "17:50");
        etLeaveEnd.setText(location.getLeaveTimeEnd() != null ? location.getLeaveTimeEnd() : "18:10");

        btnGetLocation.setOnClickListener(v -> requestLocationAndFill());

        addDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("删除", null)
                .create();

        addDialog.setOnShowListener(dialog -> {
            Button saveButton = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> saveEditedLocation(location));

            Button deleteButton = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEUTRAL);
            deleteButton.setOnClickListener(v -> {
                addDialog.dismiss();
                addDialog = null;
                int pos = locationList.indexOf(location);
                if (pos >= 0) {
                    confirmDeleteLocation(location, pos);
                }
            });
        });

        addDialog.show();
    }

    private void saveEditedLocation(final CheckInLocation location) {
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String latStr = etLatitude.getText() != null ? etLatitude.getText().toString().trim() : "";
        String lonStr = etLongitude.getText() != null ? etLongitude.getText().toString().trim() : "";
        String radiusStr = etRadius.getText() != null ? etRadius.getText().toString().trim() : "200";

        // Time range values
        boolean enterEnabled = switchEnterTime.isChecked();
        String enterStart = etEnterStart.getText() != null ? etEnterStart.getText().toString().trim() : "08:50";
        String enterEnd = etEnterEnd.getText() != null ? etEnterEnd.getText().toString().trim() : "09:03";
        boolean leaveEnabled = switchLeaveTime.isChecked();
        String leaveStart = etLeaveStart.getText() != null ? etLeaveStart.getText().toString().trim() : "17:50";
        String leaveEnd = etLeaveEnd.getText() != null ? etLeaveEnd.getText().toString().trim() : "18:10";

        if (name.isEmpty()) {
            Toast.makeText(this, "请输入地点名称", Toast.LENGTH_SHORT).show();
            return;
        }

        if (latStr.isEmpty() || lonStr.isEmpty()) {
            Toast.makeText(this, "请输入经纬度", Toast.LENGTH_SHORT).show();
            return;
        }

        double latitude, longitude;
        int radius;
        try {
            latitude = Double.parseDouble(latStr);
            longitude = Double.parseDouble(lonStr);
            radius = Integer.parseInt(radiusStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "经纬度或半径格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }

        if (radius <= 0) {
            Toast.makeText(this, "半径必须大于0", Toast.LENGTH_SHORT).show();
            return;
        }

        // Update in database
        executorService.execute(() -> {
            CheckInLocationEntity entity = database.locationDao().getLocationById(location.getId());
            if (entity != null) {
                entity.name = name;
                entity.latitude = latitude;
                entity.longitude = longitude;
                entity.radiusMeters = radius;
                entity.enterTimeEnabled = enterEnabled;
                entity.enterTimeStart = enterStart;
                entity.enterTimeEnd = enterEnd;
                entity.leaveTimeEnabled = leaveEnabled;
                entity.leaveTimeStart = leaveStart;
                entity.leaveTimeEnd = leaveEnd;
                database.locationDao().updateLocation(entity);

                mainHandler.post(() -> {
                    // Update local list
                    location.setName(name);
                    location.setLatitude(latitude);
                    location.setLongitude(longitude);
                    location.setRadiusMeters(radius);
                    location.setEnterTimeEnabled(enterEnabled);
                    location.setEnterTimeStart(enterStart);
                    location.setEnterTimeEnd(enterEnd);
                    location.setLeaveTimeEnabled(leaveEnabled);
                    location.setLeaveTimeStart(leaveStart);
                    location.setLeaveTimeEnd(leaveEnd);
                    adapter.setLocations(locationList);

                    Toast.makeText(this, "已更新: " + name, Toast.LENGTH_SHORT).show();

                    if (addDialog != null) {
                        addDialog.dismiss();
                        addDialog = null;
                    }
                });
            }
        });
    }

    @Override
    public void onToggleEnabled(CheckInLocation location, boolean enabled) {
        location.setEnabled(enabled);

        // Update in database
        executorService.execute(() ->
                database.locationDao().updateEnabled(location.getId(), enabled)
        );

        Toast.makeText(this,
                location.getName() + (enabled ? " 已启用" : " 已禁用"),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onItemClick(CheckInLocation location) {
        // Tap to edit
        showEditLocationDialog(location);
    }

    @Override
    public void onItemLongClick(CheckInLocation location) {
        // Long press also triggers edit
        showEditLocationDialog(location);
    }
}
