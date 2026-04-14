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
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * Foreground service that periodically checks GPS location
 * and triggers reminders when entering/leaving geofence areas.
 */
public class LocationMonitorService extends Service {

    private static final String CHANNEL_ID = "checkin_location_channel";
    private static final String REMINDER_CHANNEL_ID = "checkin_reminder_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REMINDER_NOTIFICATION_ID = 1002;
    private static final long CHECK_INTERVAL_MS = 30_000; // Check every 30 seconds

    // Static flag to track if service is running (shared across Activity/Service)
    public static volatile boolean isServiceRunning = false;

    /**
     * Helper method to start the service from Activity or BootReceiver.
     */
    public static void startService(Context context) {
        Intent intent = new Intent(context, LocationMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Helper method to stop the service.
     */
    public static void stopService(Context context) {
        Intent intent = new Intent(context, LocationMonitorService.class);
        context.stopService(intent);
    }

    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;

    private LocationManager locationManager;
    private AppDatabase database;

    private final Runnable checkLocationRunnable = new Runnable() {
        @Override
        public void run() {
            checkLocations();
            backgroundHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        database = AppDatabase.getInstance(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        // Create notification channel
        createNotificationChannel();

        // Start foreground with notification
        Notification notification = buildNotification("正在监控打卡位置...");
        startForeground(NOTIFICATION_ID, notification);

        // Start background location checking
        handlerThread = new HandlerThread("LocationMonitorThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        backgroundHandler.post(checkLocationRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Restart service if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low importance channel for service status
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "打卡位置监控",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("后台监控位置变化，静音");
            serviceChannel.setShowBadge(false);

            // High importance channel for reminders with sound
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

        // Get current location
        Location currentLocation = null;
        try {
            currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (currentLocation == null) {
                currentLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            return;
        }

        if (currentLocation == null) {
            return;
        }

        // Get all enabled locations from database
        List<CheckInLocationEntity> entities = database.locationDao().getAllLocations();

        for (CheckInLocationEntity entity : entities) {
            if (!entity.enabled) {
                continue;
            }

            // Convert to domain model for time range checks
            CheckInLocation location = entity.toCheckInLocation();

            // Calculate distance using Haversine formula
            float[] results = new float[1];
            Location.distanceBetween(
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude(),
                    entity.latitude,
                    entity.longitude,
                    results
            );
            float distance = results[0];

            boolean isInside = distance <= entity.radiusMeters;
            String newStatus = isInside ? "inside" : "outside";

            // Check if status changed
            if (!entity.status.equals(newStatus)) {
                String oldStatus = entity.status;

                // Update status in database
                database.locationDao().updateStatus(entity.id, newStatus);

                // Trigger reminder on status change (both entering and leaving)
                if (!"unknown".equals(oldStatus)) {
                    // Check time range constraints
                    String reminderMessage;

                    if (isInside) {
                        // Entering area - check enter time window
                        if (!location.isInEnterTimeWindow()) {
                            // Time window enabled but outside range, skip
                            continue;
                        }
                        reminderMessage = "你进入了 " + entity.name + "，请记得打卡！";
                    } else {
                        // Leaving area - check leave time window
                        if (!location.isInLeaveTimeWindow()) {
                            // Time window enabled but outside range, skip
                            continue;
                        }
                        reminderMessage = "你离开了 " + entity.name + "，请记得打卡！";
                    }

                    triggerReminder(reminderMessage);
                }
            }
        }
    }

    private void triggerReminder(String message) {
        boolean appInForeground = isAppInForeground();

        // Check settings
        boolean vibrateEnabled = SettingsActivity.isVibrationEnabled(this);
        boolean popupEnabled = SettingsActivity.isPopupEnabled(this);
        boolean notificationEnabled = SettingsActivity.isNotificationEnabled(this);
        boolean countdownEnabled = SettingsActivity.isCountdownEnabled(this);

        if (appInForeground) {
            // Show AlertDialog if enabled
            if (popupEnabled) {
                mainHandler.post(() -> showForegroundDialog(message, countdownEnabled));
            }
        } else {
            // Show notification if enabled
            if (notificationEnabled) {
                sendReminderNotification(message);
            }
        }

        // Vibrate if enabled
        if (vibrateEnabled) {
            vibrate();
        }

        // Start countdown timer if enabled
        if (countdownEnabled) {
            startCountdownTimer(message);
        }
    }

    private boolean isAppInForeground() {
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
        // Prevent multiple dialogs from stacking
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
                    if (countdownTimer != null) {
                        countdownTimer.cancel();
                    }
                    dialog.dismiss();
                })
                .setCancelable(false)
                .create();

        // Need to add SYSTEM_ALERT_WINDOW flag for dialog from service
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
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(REMINDER_NOTIFICATION_ID, notification);
        }
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }
    }

    // Keep reference to current dialog to prevent duplicates
    private AlertDialog currentDialog;

    // Countdown timer — starts once when reminder is triggered,
    // fires a final "请打卡" alert after configurable duration.
    private android.os.CountDownTimer countdownTimer;

    private void startCountdownTimer(String message) {
        mainHandler.post(() -> {
            if (countdownTimer != null) {
                countdownTimer.cancel();
            }

            final long countdownMillis = SettingsActivity.getCountdownMillis(this);

            // Play alarm sound once
            try {
                Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                android.media.Ringtone ringtone = RingtoneManager.getRingtone(this, alarmSound);
                if (ringtone != null) {
                    ringtone.play();
                }
            } catch (Exception e) {
                // Ignore
            }

            // Show a brief countdown hint in the dialog if visible
            int countdownSeconds = (int) (countdownMillis / 1000);
            if (currentDialog != null && currentDialog.isShowing()) {
                currentDialog.setMessage(message + "\n\n⏱️ " + countdownSeconds + "秒后将再次提醒…");
            }

            countdownTimer = new android.os.CountDownTimer(countdownMillis, countdownMillis) {
                @Override
                public void onTick(long millisUntilFinished) {
                    // No-op — we only care about onFinish
                }

                @Override
                public void onFinish() {
                    // Final reminder after countdown
                    vibrate();
                    sendReminderNotification(message + " ⏰ 请立即打卡！");

                    // Restore normal service notification after 5 seconds
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
