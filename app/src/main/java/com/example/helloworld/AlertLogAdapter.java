package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AlertLogAdapter extends RecyclerView.Adapter<AlertLogAdapter.ViewHolder> {

    private List<AlertLogEntity> logs = new ArrayList<>();
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AlertLogEntity log = logs.get(position);

        holder.tvAlertType.setText(log.getAlertTypeLabel());
        holder.tvLocationName.setText(log.locationName);
        holder.tvCoords.setText(String.format("纬度: %.6f, 经度: %.6f",
                log.latitude, log.longitude));
        holder.tvDistance.setText(String.format("距离: %.0f米", log.distance));
        holder.tvTime.setText(timeFormat.format(new Date(log.triggeredAt)));

        if ("enter".equals(log.alertType)) {
            holder.tvAlertType.setTextColor(0xFF4CAF50);
        } else if ("leave".equals(log.alertType)) {
            holder.tvAlertType.setTextColor(0xFFFF9800);
        }
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    public void setLogs(List<AlertLogEntity> logs) {
        this.logs = logs != null ? logs : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAlertType;
        TextView tvLocationName;
        TextView tvCoords;
        TextView tvDistance;
        TextView tvTime;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAlertType = itemView.findViewById(R.id.tvAlertType);
            tvLocationName = itemView.findViewById(R.id.tvLocationName);
            tvCoords = itemView.findViewById(R.id.tvCoords);
            tvDistance = itemView.findViewById(R.id.tvDistance);
            tvTime = itemView.findViewById(R.id.tvTime);
        }
    }
}