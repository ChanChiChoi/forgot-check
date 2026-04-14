package com.example.helloworld;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "checkin_locations")
public class CheckInLocationEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "latitude")
    public double latitude;

    @ColumnInfo(name = "longitude")
    public double longitude;

    @ColumnInfo(name = "radius_meters")
    public int radiusMeters;

    @ColumnInfo(name = "enabled")
    public boolean enabled;

    @ColumnInfo(name = "status")
    public String status; // "inside", "outside", "unknown"

    // Enter (上班靠近) time range
    @ColumnInfo(name = "enter_time_enabled")
    public boolean enterTimeEnabled;

    @ColumnInfo(name = "enter_time_start")
    public String enterTimeStart;

    @ColumnInfo(name = "enter_time_end")
    public String enterTimeEnd;

    // Leave (下班远离) time range
    @ColumnInfo(name = "leave_time_enabled")
    public boolean leaveTimeEnabled;

    @ColumnInfo(name = "leave_time_start")
    public String leaveTimeStart;

    @ColumnInfo(name = "leave_time_end")
    public String leaveTimeEnd;

    public CheckInLocationEntity() {
        this.enabled = true;
        this.status = "unknown";
        this.enterTimeEnabled = false;
        this.enterTimeStart = "08:50";
        this.enterTimeEnd = "09:03";
        this.leaveTimeEnabled = false;
        this.leaveTimeStart = "17:50";
        this.leaveTimeEnd = "18:10";
    }

    @androidx.room.Ignore
    public CheckInLocationEntity(String name, double latitude, double longitude, int radiusMeters) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.enabled = true;
        this.status = "unknown";
        this.enterTimeEnabled = false;
        this.enterTimeStart = "08:50";
        this.enterTimeEnd = "09:03";
        this.leaveTimeEnabled = false;
        this.leaveTimeStart = "17:50";
        this.leaveTimeEnd = "18:10";
    }

    // Convert to domain model
    public CheckInLocation toCheckInLocation() {
        CheckInLocation location = new CheckInLocation(name, latitude, longitude, radiusMeters);
        location.setId(id);
        location.setEnabled(enabled);
        location.setStatus(status);
        location.setEnterTimeEnabled(enterTimeEnabled);
        location.setEnterTimeStart(enterTimeStart);
        location.setEnterTimeEnd(enterTimeEnd);
        location.setLeaveTimeEnabled(leaveTimeEnabled);
        location.setLeaveTimeStart(leaveTimeStart);
        location.setLeaveTimeEnd(leaveTimeEnd);
        return location;
    }

    // Create from domain model
    public static CheckInLocationEntity fromCheckInLocation(CheckInLocation location) {
        CheckInLocationEntity entity = new CheckInLocationEntity(
                location.getName(),
                location.getLatitude(),
                location.getLongitude(),
                location.getRadiusMeters()
        );
        entity.id = location.getId();
        entity.enabled = location.isEnabled();
        entity.status = location.getStatus();
        entity.enterTimeEnabled = location.isEnterTimeEnabled();
        entity.enterTimeStart = location.getEnterTimeStart();
        entity.enterTimeEnd = location.getEnterTimeEnd();
        entity.leaveTimeEnabled = location.isLeaveTimeEnabled();
        entity.leaveTimeStart = location.getLeaveTimeStart();
        entity.leaveTimeEnd = location.getLeaveTimeEnd();
        return entity;
    }
}
