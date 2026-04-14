package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class CheckInLocationAdapter extends RecyclerView.Adapter<CheckInLocationAdapter.ViewHolder> {

    public interface OnLocationActionListener {
        void onToggleEnabled(CheckInLocation location, boolean enabled);
        void onItemClick(CheckInLocation location);
        void onItemLongClick(CheckInLocation location);
    }

    private List<CheckInLocation> locations = new ArrayList<>();
    private OnLocationActionListener listener;

    public CheckInLocationAdapter(OnLocationActionListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_checkin_location, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CheckInLocation location = locations.get(position);

        holder.tvName.setText(location.getName());
        holder.tvCoords.setText(String.format("纬度: %.6f, 经度: %.6f",
                location.getLatitude(), location.getLongitude()));
        holder.tvRadius.setText(String.format("半径: %d米", location.getRadiusMeters()));

        String statusText;
        switch (location.getStatus()) {
            case "inside":
                statusText = "状态: 已进入";
                holder.tvStatus.setTextColor(0xFF4CAF50);
                break;
            case "outside":
                statusText = "状态: 已离开";
                holder.tvStatus.setTextColor(0xFFFF9800);
                break;
            default:
                statusText = "状态: 未知";
                holder.tvStatus.setTextColor(0xFF999999);
                break;
        }
        holder.tvStatus.setText(statusText);

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(location.isEnabled());
        holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) {
                listener.onToggleEnabled(location, isChecked);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(location);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onItemLongClick(location);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return locations.size();
    }

    public void setLocations(List<CheckInLocation> locations) {
        this.locations = locations != null ? locations : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateItem(int position, CheckInLocation location) {
        if (position >= 0 && position < locations.size()) {
            locations.set(position, location);
            notifyItemChanged(position);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvCoords;
        TextView tvRadius;
        TextView tvStatus;
        SwitchCompat switchEnabled;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvCoords = itemView.findViewById(R.id.tvCoords);
            tvRadius = itemView.findViewById(R.id.tvRadius);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            switchEnabled = itemView.findViewById(R.id.switchEnabled);
        }
    }
}
