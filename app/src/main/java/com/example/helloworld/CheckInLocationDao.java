package com.example.helloworld;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface CheckInLocationDao {

    @Query("SELECT * FROM checkin_locations ORDER BY id DESC")
    List<CheckInLocationEntity> getAllLocations();

    @Query("SELECT * FROM checkin_locations WHERE id = :id")
    CheckInLocationEntity getLocationById(long id);

    @Insert
    long insertLocation(CheckInLocationEntity location);

    @Update
    void updateLocation(CheckInLocationEntity location);

    @Delete
    void deleteLocation(CheckInLocationEntity location);

    @Query("UPDATE checkin_locations SET enabled = :enabled WHERE id = :id")
    void updateEnabled(long id, boolean enabled);

    @Query("UPDATE checkin_locations SET status = :status WHERE id = :id")
    void updateStatus(long id, String status);

    @Query("UPDATE checkin_locations SET " +
            "enter_time_enabled = :enterEnabled, enter_time_start = :enterStart, enter_time_end = :enterEnd, " +
            "leave_time_enabled = :leaveEnabled, leave_time_start = :leaveStart, leave_time_end = :leaveEnd " +
            "WHERE id = :id")
    void updateTimeRanges(long id, boolean enterEnabled, String enterStart, String enterEnd,
                          boolean leaveEnabled, String leaveStart, String leaveEnd);

    @Query("UPDATE checkin_locations SET was_in_enter_time_window = :inEnterWindow, " +
            "was_in_leave_time_window = :inLeaveWindow WHERE id = :id")
    void updateTimeWindowState(long id, boolean inEnterWindow, boolean inLeaveWindow);

    @Query("DELETE FROM checkin_locations")
    void deleteAll();
}
