package com.example.helloworld;

public class CheckInLocation {
    private long id;
    private String name;
    private double latitude;
    private double longitude;
    private int radiusMeters;
    private boolean enabled;
    // "inside", "outside", or "unknown"
    private String status;

    // Time range for "enter" reminder (上班靠近)
    private boolean enterTimeEnabled;
    private String enterTimeStart;   // e.g. "08:50"
    private String enterTimeEnd;     // e.g. "09:03"

    // Time range for "leave" reminder (下班远离)
    private boolean leaveTimeEnabled;
    private String leaveTimeStart;   // e.g. "17:50"
    private String leaveTimeEnd;     // e.g. "18:10"

    private boolean wasInEnterTimeWindow;
    private boolean wasInLeaveTimeWindow;

    public CheckInLocation() {
        this.status = "unknown";
        this.enabled = true;
        this.enterTimeEnabled = false;
        this.enterTimeStart = "08:50";
        this.enterTimeEnd = "09:03";
        this.leaveTimeEnabled = false;
        this.leaveTimeStart = "17:50";
        this.leaveTimeEnd = "18:10";
    }

    public CheckInLocation(String name, double latitude, double longitude, int radiusMeters) {
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

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public int getRadiusMeters() { return radiusMeters; }
    public void setRadiusMeters(int radiusMeters) { this.radiusMeters = radiusMeters; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // === Enter (上班靠近) time range ===
    public boolean isEnterTimeEnabled() { return enterTimeEnabled; }
    public void setEnterTimeEnabled(boolean enterTimeEnabled) { this.enterTimeEnabled = enterTimeEnabled; }
    public String getEnterTimeStart() { return enterTimeStart; }
    public void setEnterTimeStart(String enterTimeStart) { this.enterTimeStart = enterTimeStart; }
    public String getEnterTimeEnd() { return enterTimeEnd; }
    public void setEnterTimeEnd(String enterTimeEnd) { this.enterTimeEnd = enterTimeEnd; }

    // === Leave (下班远离) time range ===
    public boolean isLeaveTimeEnabled() { return leaveTimeEnabled; }
    public void setLeaveTimeEnabled(boolean leaveTimeEnabled) { this.leaveTimeEnabled = leaveTimeEnabled; }
    public String getLeaveTimeStart() { return leaveTimeStart; }
    public void setLeaveTimeStart(String leaveTimeStart) { this.leaveTimeStart = leaveTimeStart; }
    public String getLeaveTimeEnd() { return leaveTimeEnd; }
    public void setLeaveTimeEnd(String leaveTimeEnd) { this.leaveTimeEnd = leaveTimeEnd; }

    public boolean wasInEnterTimeWindow() { return wasInEnterTimeWindow; }
    public void setWasInEnterTimeWindow(boolean wasInEnterTimeWindow) { this.wasInEnterTimeWindow = wasInEnterTimeWindow; }
    public boolean wasInLeaveTimeWindow() { return wasInLeaveTimeWindow; }
    public void setWasInLeaveTimeWindow(boolean wasInLeaveTimeWindow) { this.wasInLeaveTimeWindow = wasInLeaveTimeWindow; }

    /**
     * Check if current time falls within the enter (上班) time window.
     * Returns true if time window is disabled (always allow).
     */
    public boolean isInEnterTimeWindow() {
        if (!enterTimeEnabled) return true; // disabled = always remind
        return isTimeInRange(enterTimeStart, enterTimeEnd);
    }

    /**
     * Check if current time falls within the leave (下班) time window.
     * Returns true if time window is disabled (always allow).
     */
    public boolean isInLeaveTimeWindow() {
        if (!leaveTimeEnabled) return true; // disabled = always remind
        return isTimeInRange(leaveTimeStart, leaveTimeEnd);
    }

    private boolean isTimeInRange(String start, String end) {
        try {
            String[] s = start.split(":");
            String[] e = end.split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);

            java.util.Calendar cal = java.util.Calendar.getInstance();
            int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);

            if (startMin <= endMin) {
                return nowMin >= startMin && nowMin <= endMin;
            } else {
                // Crosses midnight (e.g. 23:00 - 01:00)
                return nowMin >= startMin || nowMin <= endMin;
            }
        } catch (Exception ex) {
            return true;
        }
    }
}
