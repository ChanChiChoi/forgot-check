package com.example.helloworld;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AlertLogDao {

    @Query("SELECT * FROM alert_logs ORDER BY triggered_at DESC")
    List<AlertLogEntity> getAllLogs();

    @Query("SELECT * FROM alert_logs ORDER BY triggered_at DESC LIMIT :limit")
    List<AlertLogEntity> getRecentLogs(int limit);

    @Insert
    long insertLog(AlertLogEntity log);

    @Query("DELETE FROM alert_logs")
    void deleteAll();

    @Query("DELETE FROM alert_logs WHERE triggered_at < :timestamp")
    void deleteOldLogs(long timestamp);

    @Query("SELECT COUNT(*) FROM alert_logs")
    int getLogCount();
}