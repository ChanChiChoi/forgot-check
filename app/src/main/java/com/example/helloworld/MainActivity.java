package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 1003;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1004;

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvServiceStatus;
    private FloatingActionButton fabAdd;
    private CheckInLocationAdapter adapter;
    private List<CheckInLocation> locationList = new ArrayList<>();

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
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        fabAdd = findViewById(R.id.fabAdd);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CheckInLocationAdapter(this);
        recyclerView.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showAddLocationDialog());

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

        // Initialize database
        database = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        // Load data from database
        loadLocationsFromDatabase();
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
            }
        }
    }

    private void toggleLocationService() {
        // Check if we have fine location permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        // Request background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        BACKGROUND_LOCATION_REQUEST_CODE);
                return;
            }
        }

        // Check if service is running (simple check)
        boolean serviceRunning = LocationMonitorService.isServiceRunning;

        if (serviceRunning) {
            LocationMonitorService.stopService(this);
            LocationMonitorService.isServiceRunning = false;
            Toast.makeText(this, "位置监控已停止", Toast.LENGTH_SHORT).show();
        } else {
            LocationMonitorService.startService(this);
            LocationMonitorService.isServiceRunning = true;
            Toast.makeText(this, "位置监控已启动", Toast.LENGTH_LONG).show();
        }

        updateServiceStatus();
    }

    private void updateServiceStatus() {
        boolean running = LocationMonitorService.isServiceRunning;
        tvServiceStatus.setText(running ? "监控: 运行中" : "监控: 未启动");
        tvServiceStatus.setTextColor(running ? 0xFF4CAF50 : 0xFFFF9800);
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
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, "需要位置权限才能获取当前位置", Toast.LENGTH_SHORT).show();
                tvLocationStatus.setText("权限被拒绝，请手动输入经纬度");
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Background location granted, now start the service
                LocationMonitorService.startService(this);
                LocationMonitorService.isServiceRunning = true;
                Toast.makeText(this, "位置监控已启动", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "后台位置权限被拒绝，监控服务可能无法正常工作",
                        Toast.LENGTH_LONG).show();
            }
            updateServiceStatus();
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
