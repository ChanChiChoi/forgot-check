package com.example.helloworld;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "alert_logs")
public class AlertLogEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "location_id")
    public long locationId;

    @ColumnInfo(name = "location_name")
    public String locationName;

    @ColumnInfo(name = "alert_type")
    public String alertType;

    @ColumnInfo(name = "latitude")
    public double latitude;

    @ColumnInfo(name = "longitude")
    public double longitude;

    @ColumnInfo(name = "distance")
    public float distance;

    @ColumnInfo(name = "triggered_at")
    public long triggeredAt;

    public AlertLogEntity() {
    }

    @androidx.room.Ignore
    public AlertLogEntity(long locationId, String locationName, String alertType,
                           double latitude, double longitude, float distance) {
        this.locationId = locationId;
        this.locationName = locationName;
        this.alertType = alertType;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distance = distance;
        this.triggeredAt = System.currentTimeMillis();
    }

    public String getFormattedTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(triggeredAt));
    }

    public String getAlertTypeLabel() {
        if ("enter".equals(alertType)) {
            return "进入提醒";
        } else if ("leave".equals(alertType)) {
            return "离开提醒";
        }
        return alertType;
    }
}