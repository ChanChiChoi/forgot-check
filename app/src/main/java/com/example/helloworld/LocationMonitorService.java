package com.example.helloworld;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that actively requests GPS location
 * and triggers reminders when entering/leaving geofence areas.
 *
 * GPS polling strategy:
 * - If ANY enabled location has its time range switch OFF → poll every 15s always
 * - If ALL enabled locations have their time range switches ON → only poll during active windows
 * - When service is stopped → completely silent, no GPS requests
 */
public class LocationMonitorService extends Service {

    private static final String CHANNEL_ID = "checkin_location_channel";
    private static final String REMINDER_CHANNEL_ID = "checkin_reminder_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REMINDER_NOTIFICATION_ID = 1002;
    private static final long CHECK_INTERVAL_MS = 15_000; // Active GPS request every 15 seconds

    public static volatile boolean isServiceRunning = false;
    public static volatile boolean isActivityVisible = false;

    public static void setActivityVisible(boolean visible) {
        isActivityVisible = visible;
    }

    public static void startService(Context context) {
        Intent intent = new Intent(context, LocationMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopService(Context context) {
        Intent intent = new Intent(context, LocationMonitorService.class);
        context.stopService(intent);
    }

    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;

    private LocationManager locationManager;
    private AppDatabase database;
    private PowerManager.WakeLock wakeLock;

    // Active GPS location listener
    private LocationListener locationListener;
    private volatile Location currentLocation;

    private final Runnable checkLocationRunnable = new Runnable() {
        @Override
        public void run() {
            checkLocations();
            scheduleNextCheck();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        database = AppDatabase.getInstance(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        executorServiceForLogs = Executors.newSingleThreadExecutor();

        // WakeLock to keep CPU awake for GPS polling when screen is off
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WangDaKa::LocationPoll"
        );

        createNotificationChannel();

        Notification notification = buildNotification("正在监控打卡位置...");
        startForeground(NOTIFICATION_ID, notification);

        // Setup location listener for active GPS requests
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                currentLocation = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        // Start background location checking
        handlerThread = new HandlerThread("LocationMonitorThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        backgroundHandler.post(checkLocationRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;

        // Stop active GPS requests completely
        if (locationListener != null && locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception e) {
                // Ignore
            }
        }

        backgroundHandler.removeCallbacks(checkLocationRunnable);
        if (handlerThread != null) {
            handlerThread.quit();
        }
        if (countdownTimer != null) {
            countdownTimer.cancel();
        }
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Shutdown log executor
        if (executorServiceForLogs != null) {
            executorServiceForLogs.shutdown();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Schedule next GPS check. Always poll every 15s — status is always updated,
     * only the reminder trigger is gated by time window checks.
     */
    private void scheduleNextCheck() {
        backgroundHandler.postDelayed(checkLocationRunnable, CHECK_INTERVAL_MS);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "打卡位置监控",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("后台监控位置变化，静音");
            serviceChannel.setShowBadge(false);

            NotificationChannel reminderChannel = new NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "打卡提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            reminderChannel.setDescription("进出围栏时提醒，带铃声和震动");
            reminderChannel.enableVibration(true);
            reminderChannel.setVibrationPattern(new long[]{0, 500, 200, 500});

            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            reminderChannel.setSound(alarmSound, attributes);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                manager.createNotificationChannel(reminderChannel);
            }
        }
    }

    private Notification buildNotification(String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("忘打卡")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void checkLocations() {
        // Check permissions
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Acquire WakeLock to keep CPU awake during GPS polling (screen-off scenario)
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30_000); // Max 30 seconds timeout safeguard
        }

        // Actively request current GPS location (blocking call with timeout)
        Location location = null;
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0, 0, locationListener);

            // Wait briefly for a location fix (up to 10 seconds)
            long startTime = System.currentTimeMillis();
            while (currentLocation == null && (System.currentTimeMillis() - startTime) < 10_000) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            location = currentLocation;

            // Remove updates to save battery — we'll request again next cycle
            locationManager.removeUpdates(locationListener);
            currentLocation = null;

            // Fallback to network if GPS failed
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            // Release WakeLock on exception
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            return;
        }

        if (location == null) {
            // Release WakeLock if no location obtained
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            return;
        }

        // Get all enabled locations from database
        List<CheckInLocationEntity> entities = database.locationDao().getAllLocations();

        for (CheckInLocationEntity entity : entities) {
            if (!entity.enabled) {
                continue;
            }

            CheckInLocation checkInLocation = entity.toCheckInLocation();

            // Calculate distance using Haversine formula
            float[] results = new float[1];
            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(),
                    entity.latitude,
                    entity.longitude,
                    results
            );
            float distance = results[0];

            boolean isInside = distance <= entity.radiusMeters;
            String newStatus = isInside ? "inside" : "outside";

            // Always update status in database (even if outside time window)
            if (!entity.status.equals(newStatus)) {
                String oldStatus = entity.status;

                // Update status in database
                database.locationDao().updateStatus(entity.id, newStatus);

                // Trigger reminder on status change ONLY if time window allows
                if (!"unknown".equals(oldStatus)) {
                    boolean timeWindowAllows = isInside
                            ? checkInLocation.isInEnterTimeWindow()
                            : checkInLocation.isInLeaveTimeWindow();

                    if (timeWindowAllows) {
                        String reminderMessage = isInside
                                ? "你进入了 " + entity.name + "，请记得打卡！"
                                : "你离开了 " + entity.name + "，请记得打卡！";
                        logAlert(entity, isInside ? "enter" : "leave", location);
                        triggerReminder(reminderMessage);
                    }
                }
            }
        }

        // Release WakeLock after all processing is done
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void triggerReminder(String message) {
        boolean appInForeground = isAppInForeground();

        boolean vibrateEnabled = SettingsActivity.isVibrationEnabled(this);
        boolean popupEnabled = SettingsActivity.isPopupEnabled(this);
        boolean notificationEnabled = SettingsActivity.isNotificationEnabled(this);
        boolean countdownEnabled = SettingsActivity.isCountdownEnabled(this);

        if (appInForeground) {
            if (popupEnabled) {
                mainHandler.post(() -> showForegroundDialog(message, countdownEnabled));
            }
        } else {
            if (notificationEnabled) {
                sendReminderNotification(message);
            }
        }

        if (vibrateEnabled) {
            vibrate();
        }

        if (countdownEnabled) {
            startCountdownTimer(message);
        }
    }

    private void logAlert(CheckInLocationEntity entity, String alertType, Location location) {
        if (location == null) return;

        float[] results = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                entity.latitude,
                entity.longitude,
                results
        );

        AlertLogEntity log = new AlertLogEntity(
                entity.id,
                entity.name,
                alertType,
                location.getLatitude(),
                location.getLongitude(),
                results[0]
        );

        executorServiceForLogs.execute(() -> {
            database.alertLogDao().insertLog(log);
        });
    }

    private ExecutorService executorServiceForLogs;

    private boolean isAppInForeground() {
        if (!isActivityVisible) {
            return false;
        }
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) return false;

        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) return false;

        for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
            if (processInfo.processName.equals(getPackageName())
                    && processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    private void showForegroundDialog(String message, boolean showCountdown) {
        if (currentDialog != null && currentDialog.isShowing()) {
            return;
        }

        String dialogMessage = message;
        if (showCountdown) {
            dialogMessage = message + "\n\n10秒倒计时提醒即将开始...";
        }

        currentDialog = new AlertDialog.Builder(this)
                .setTitle("⏰ 打卡提醒")
                .setMessage(dialogMessage)
                .setPositiveButton("知道了", (dialog, which) -> {
                    // Do NOT cancel countdown timer — let it run to trigger follow-up reminder
                    dialog.dismiss();
                })
                .setCancelable(false)
                .create();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentDialog.getWindow().setType(
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            currentDialog.getWindow().setType(
                    android.view.WindowManager.LayoutParams.TYPE_PHONE);
        }

        currentDialog.show();
    }

    private void sendReminderNotification(String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        Notification notification = new NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
                .setContentTitle("忘打卡 - 打卡提醒")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setSound(alarmSound)
                .setVibrate(new long[]{0, 500, 200, 500})
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(2000);
            }
        }
    }

    private AlertDialog currentDialog;
    private android.os.CountDownTimer countdownTimer;

    private void startCountdownTimer(String message) {
        mainHandler.post(() -> {
            if (countdownTimer != null) {
                countdownTimer.cancel();
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

            int countdownSeconds = (int) (countdownMillis / 1000);
            if (currentDialog != null && currentDialog.isShowing()) {
                currentDialog.setMessage(message + "\n\n⏱️ " + countdownSeconds + "秒后将再次提醒…");
            }

            countdownTimer = new android.os.CountDownTimer(countdownMillis, countdownMillis) {
                @Override
                public void onTick(long millisUntilFinished) {}

                @Override
                public void onFinish() {
                    vibrate();
                    sendReminderNotification(message + " ⏰ 请立即打卡！");

                    mainHandler.postDelayed(() -> {
                        Notification normalNotification = buildNotification("正在监控打卡位置...");
                        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (manager != null) {
                            manager.notify(NOTIFICATION_ID, normalNotification);
                        }
                    }, 5000);
                }
            }.start();
        });
    }
}
