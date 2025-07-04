package com.example.bluetoothscanner;

import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder> {

    private final List<BluetoothDevice> devices;
    private final OnDeviceClickListener onDeviceClick;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device, View view);
    }

    public DeviceListAdapter(List<BluetoothDevice> devices, OnDeviceClickListener onDeviceClick) {
        this.devices = devices;
        this.onDeviceClick = onDeviceClick;
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        public final TextView deviceName;
        public final TextView deviceAddress;

        public DeviceViewHolder(View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.device_name);
            deviceAddress = itemView.findViewById(R.id.device_address);
        }
    }

    @Override
    public DeviceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(DeviceViewHolder holder, int position) {
        BluetoothDevice device = devices.get(position);
        holder.deviceName.setText(device.getName() != null ? device.getName() : "Unknown Device");
        holder.deviceAddress.setText(device.getAddress());
        holder.itemView.setOnClickListener(v -> onDeviceClick.onDeviceClick(device, holder.itemView));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }
}