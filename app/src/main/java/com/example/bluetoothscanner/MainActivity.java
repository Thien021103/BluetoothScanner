package com.example.bluetoothscanner;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private BluetoothAdapter bluetoothAdapter;
    private TextView statusText;
    private Button scanButton;
    private Button sendButton; // New button for sending messages
    private EditText messageInput; // Input field for messages
    private ProgressBar progressBar;
    private Switch bleToggle;
    private TextView characteristicValuesText;
    private RecyclerView deviceList;
    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private DeviceListAdapter deviceListAdapter;

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // UUID for SPP
    private static final String TARGET_MAC_ADDRESS = "94:08:53:71:50:F6"; // Target MAC address

    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private int bleMtu = 512;
    private BluetoothSocket socket;
    private final StringBuilder messageBuilder = new StringBuilder();

    // Activity Result Launcher for enabling Bluetooth
    private final ActivityResultLauncher<Intent> enableBluetoothLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
//                    if (bleToggle.isChecked()) startBleScan();
//                    else startDiscovery();
                } else {
                    Toast.makeText(this, "Bluetooth must be enabled to use", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
    );

    interface BluetoothEnabledCallback {
        void onEnabled();
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanButton);
        sendButton = findViewById(R.id.sendButton);
        messageInput = findViewById(R.id.messageInput);
        progressBar = findViewById(R.id.progressBar);
        deviceList = findViewById(R.id.deviceList);
        bleToggle = findViewById(R.id.bleToggle);
        characteristicValuesText = findViewById(R.id.characteristicValuesText); // Initialize new TextView
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        deviceListAdapter = new DeviceListAdapter(discoveredDevices, this::showDeviceMenu);
        deviceList.setAdapter(deviceListAdapter);
        deviceList.setLayoutManager(new LinearLayoutManager(this));

        // Check BLE support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Device does not support BLE", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        scanButton.setOnClickListener(v -> {
            if (socket != null && socket.isConnected()) {
                try {
                    socket.close();
                    socket = null;
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected (Classic)");
                        Toast.makeText(MainActivity.this, "Disconnect successful", Toast.LENGTH_SHORT).show();
                    });
                } catch (IOException e) {
                    Log.e("Bluetooth", "Error closing socket", e);
                }
            } else if (bluetoothGatt != null) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
                deviceList.setVisibility(VISIBLE);
                characteristicValuesText.setVisibility(View.GONE);
                statusText.setText("Disconnected (BLE)");
                Toast.makeText(MainActivity.this, "Disconnected BLE", Toast.LENGTH_SHORT).show();
            } else {
                boolean useBle = bleToggle.isChecked();
                Log.d("Bluetooth", "Starting scan, useBle: " + useBle);
                deviceList.setVisibility(VISIBLE);
                characteristicValuesText.setVisibility(View.GONE);
                if (useBle) startBleScan();
                else startDiscovery();
            }
        });

        sendButton.setOnClickListener(v -> {
            String message = messageInput.getText().toString();
            if (!message.isEmpty()) {
                if (socket != null && socket.isConnected()) {
                    // Send via Bluetooth Classic
                    new Thread(() -> {
                        try {
                            socket.getOutputStream().write((message + "\n").getBytes());
                            socket.getOutputStream().flush();
                            runOnUiThread(() -> {
                                messageInput.setText("");
                                Toast.makeText(MainActivity.this, "Sent (Classic): " + message, Toast.LENGTH_SHORT).show();
                            });
                        } catch (IOException e) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Error sending (Classic): " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                } else if (bluetoothGatt != null && writeCharacteristic != null) {
                    // Send via BLE
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        // Split data into chunks based on MTU
                        int chunkSize = bleMtu - 3; // Subtract header
                        byte[] data = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        for (int i = 0; i < data.length; i += chunkSize) {
                            int end = Math.min(i + chunkSize, data.length);
                            byte[] chunk = Arrays.copyOfRange(data, i, end);
                            bluetoothGatt.writeCharacteristic(
                                    writeCharacteristic,
                                    chunk,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            );
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Log.e("BLE", "Sleep interrupted", e);
                            }
                        }
                        runOnUiThread(() -> {
                            messageInput.setText("");
                            Toast.makeText(MainActivity.this, "Sent " + data.length + " bytes", Toast.LENGTH_SHORT).show();
                        });
                        Log.d("BLE", "Sent " + data.length + " bytes");
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Not connected or no write characteristic", Toast.LENGTH_SHORT).show();
                    Log.d("BLE", "Not connected or no write characteristic");
                }
            } else {
                Toast.makeText(MainActivity.this, "Empty message", Toast.LENGTH_SHORT).show();
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, filter);
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void showDeviceMenu(BluetoothDevice device, View anchorView) {
        PopupMenu popupMenu = new PopupMenu(this, anchorView);
        popupMenu.getMenu().add("Connect (Classic)");
        popupMenu.getMenu().add("Connect (BLE)");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            popupMenu.getMenu().add("Pair");
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Connect (Classic)":
                    connectToDevice(device); // Connect via Bluetooth Classic
                    return true;
                case "Connect (BLE)":
                    connectToBleDevice(device); // Connect via BLE
                    return true;
                case "Pair":
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.createBond();
                        Toast.makeText(MainActivity.this, "Pairing with " + device.getName(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                default:
                    return false;
            }
        });
        popupMenu.show();
    }

    private void checkPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkBluetoothEnabled(() -> {});
            } else {
                Toast.makeText(this, "Permissions required for Bluetooth", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void checkBluetoothEnabled(BluetoothEnabledCallback callback) {
        if (!bluetoothAdapter.isEnabled()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show();
                checkPermissions();
                return;
            }
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            callback.onEnabled();
        }
    }

    // -------------- Classic setup start --------------
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Please pair with " + device.getName() + " first", Toast.LENGTH_SHORT).show();
                statusText.setText("Need to pair with " + device.getName());
            });
            device.createBond();
            return;
        }
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        statusText.setText("Connecting to " + device.getName() + "...");
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                socket.connect();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Connected to " + device.getName(), Toast.LENGTH_SHORT).show();
                    statusText.setText("Connected to " + device.getName());
                    messageBuilder.setLength(0); // Clear old messages
                    try {
                        deviceList.setVisibility(View.GONE);
                        characteristicValuesText.setVisibility(View.VISIBLE);
                    } catch (Exception e) {
                        runOnUiThread(() -> Log.e("Bluetooth", "Error: " + e.getMessage(), e));
                    }
                });

                java.io.OutputStream outputStream = socket.getOutputStream();
                java.io.InputStream inputStream = socket.getInputStream();

                // Send initial message
                String messages = "Hello from Android!";
                outputStream.write(messages.getBytes());
                outputStream.flush();
                Thread.sleep(500);

                // Read/receive message loop
                byte[] buffer = new byte[1024];
                while (socket.isConnected()) {
                    try {
                        int bytes = inputStream.read(buffer);
                        if (bytes == -1) {
                            throw new IOException("Connection closed");
                        }
                        String received = new String(buffer, 0, bytes);
                        Log.d("Bluetooth", "Received: " + received);
                        messageBuilder.append(received);
                        characteristicValuesText.setText(messageBuilder.toString());
                    } catch (IOException e) {
                        runOnUiThread(() -> Log.e("Bluetooth", "Error reading data: " + e.getMessage(), e));
                        break;
                    }
                }
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Connection error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    statusText.setText("Error with " + device.getName() + ": " + e.getMessage());
                    Log.e("Bluetooth", "Connection error", e);
                    deviceList.setVisibility(VISIBLE);
                    characteristicValuesText.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    statusText.setText("Error with " + device.getName() + ": " + e.getMessage());
                    Log.e("Bluetooth", "Error", e);
                    deviceList.setVisibility(VISIBLE);
                    characteristicValuesText.setVisibility(View.GONE);
                });
            } finally {
                if (socket != null && socket.isConnected()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        Log.e("Bluetooth", "Error closing socket", e);
                    }
                }
                socket = null;
                runOnUiThread(() -> {
                    deviceList.setVisibility(VISIBLE);
                    characteristicValuesText.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        AtomicBoolean bluetoothEnabled = new AtomicBoolean(false);
        checkBluetoothEnabled(() -> bluetoothEnabled.set(true));
        if (!bluetoothEnabled.get()) {
            Toast.makeText(this, "Bluetooth must be enabled to use", Toast.LENGTH_LONG).show();
            return;
        }
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();
        boolean started = bluetoothAdapter.startDiscovery();
        Log.d("Bluetooth", "startDiscovery called, result: " + started);
        progressBar.setVisibility(VISIBLE);
        scanButton.setEnabled(false);
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    statusText.setText("Scanning for devices (Classic scan)...");
                    progressBar.setVisibility(VISIBLE);
                    break;
                case BluetoothDevice.ACTION_FOUND:
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && !discoveredDevices.contains(device)) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            discoveredDevices.add(device);
                            deviceListAdapter.notifyItemInserted(discoveredDevices.size() - 1);
                        }
                    }
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    statusText.setText(discoveredDevices.isEmpty() ? "No devices found" : "Found " + discoveredDevices.size() + " devices");
                    progressBar.setVisibility(View.GONE);
                    scanButton.setEnabled(true);
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    BluetoothDevice bondDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    String stateText;
                    switch (state) {
                        case BluetoothDevice.BOND_BONDED:
                            stateText = "Paired";
                            break;
                        case BluetoothDevice.BOND_BONDING:
                            stateText = "Pairing";
                            break;
                        case BluetoothDevice.BOND_NONE:
                            stateText = "Not paired";
                            break;
                        default:
                            stateText = "Unknown";
                            break;
                    }
                    if (bondDevice != null) {
                        statusText.setText("Bonding state of " + bondDevice.getName() + " changed to " + stateText);
                    }
                    break;
            }
        }
    };
    // -------------- Classic setup end --------------

    // -------------- BLE setup start --------------
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null) {
                if (device.getAddress().equalsIgnoreCase(TARGET_MAC_ADDRESS)) {
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device);
                        deviceListAdapter.notifyItemInserted(discoveredDevices.size() - 1);
                        runOnUiThread(() -> {
                            statusText.setText("Found device " + TARGET_MAC_ADDRESS + ", connecting...");
                            connectToBleDevice(device);
                        });
                    }
                } else {
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device);
                        deviceListAdapter.notifyItemInserted(discoveredDevices.size() - 1);
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e("BLE", "Scan failed with error code: " + errorCode);
            runOnUiThread(() -> {
                statusText.setText("BLE scan error: " + errorCode);
                progressBar.setVisibility(View.GONE);
                scanButton.setEnabled(true);
                Toast.makeText(MainActivity.this, "Scan failed: " + errorCode, Toast.LENGTH_LONG).show();
            });
        }
    };

    private void startBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        AtomicBoolean bluetoothEnabled = new AtomicBoolean(false);
        checkBluetoothEnabled(() -> bluetoothEnabled.set(true));
        if (!bluetoothEnabled.get()) {
            Toast.makeText(this, "Bluetooth must be enabled to use", Toast.LENGTH_LONG).show();
            return;
        }
        stopBleScan();
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();
        bluetoothLeScanner.startScan(scanCallback);
        statusText.setText("Scanning for devices (BLE scan)\nTarget: " + TARGET_MAC_ADDRESS + "...");
        progressBar.setVisibility(VISIBLE);
        scanButton.setEnabled(false);
    }

    private void stopBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner.stopScan(scanCallback);
            statusText.setText("Stopped BLE scan");
            progressBar.setVisibility(View.GONE);
            scanButton.setEnabled(true);
        }
    }

    private void connectToBleDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        Log.d("BLE", "Connecting to " + device.getAddress());
        stopBleScan();
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_2M);
        statusText.setText("Connecting to " + (device.getName() != null ? device.getName() : device.getAddress()) + " (BLE)...");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        private final StringBuilder characteristicValues = new StringBuilder();
        private List<BluetoothGattCharacteristic> readableCharacteristics;
        private int currentReadIndex = 0;
        private final List<BluetoothGattDescriptor> descriptorsToWrite = new ArrayList<>();
        private int currentDescriptorIndex = 0;
        private boolean descriptorsWritten = false;
        private boolean startedReading = false;


        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread(() -> {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        statusText.setText("Connected to " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()) + " (BLE)");
                        Toast.makeText(MainActivity.this, "Connected to " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()), Toast.LENGTH_SHORT).show();
                        deviceList.setVisibility(View.GONE);
                        characteristicValuesText.setVisibility(View.VISIBLE);
                        characteristicValues.setLength(0);
                        characteristicValuesText.setText("Reading characteristics...");
                    });
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.discoverServices();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected from " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()));
                        Toast.makeText(MainActivity.this, "Disconnected from " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()), Toast.LENGTH_SHORT).show();
                        deviceList.setVisibility(View.VISIBLE);
//                        characteristicValuesText.setVisibility(View.GONE);
                        startedReading = false;
                    });
                    gatt.close();
                    bluetoothGatt = null;
                }
            } else {
                runOnUiThread(() -> {
                    statusText.setText("BLE connection error: " + status + " " + newState);
                    Toast.makeText(MainActivity.this, "BLE connection error: " + status + " " + newState, Toast.LENGTH_LONG).show();
                    deviceList.setVisibility(View.VISIBLE);
//                    characteristicValuesText.setVisibility(View.GONE);
                    startedReading = false;
                });
                gatt.close();
                bluetoothGatt = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                runOnUiThread(() -> statusText.setText("Discovered services from " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress())));
                readableCharacteristics = new ArrayList<>();
                characteristicValues.setLength(0);
                currentReadIndex = 0;
                descriptorsToWrite.clear();
                currentDescriptorIndex = 0;
                descriptorsWritten = false;

                // Collect readable characteristics and descriptors for notifications
                gatt.getServices().forEach(service -> {
                    Log.d("BLE", "Service UUID: " + service.getUuid());
                    service.getCharacteristics().forEach(characteristic -> {
                        Log.d("BLE", "Characteristic UUID: " + characteristic.getUuid() + ", Properties: " + characteristic.getProperties());
                        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            readableCharacteristics.add(characteristic);
                        }
                        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                            writeCharacteristic = characteristic;
                        }
                        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                gatt.setCharacteristicNotification(characteristic, true);
                                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }
                        }
                    });
                });
                if (!startedReading && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    startedReading = true;
                    startReadingCharacteristics(gatt);
                }
            } else {
                runOnUiThread(() -> statusText.setText("Service discovery error: " + status));
            }
        }

        private void startReadingCharacteristics(BluetoothGatt gatt) {
            if (!readableCharacteristics.isEmpty() && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                Log.d("BLE", "Number of readable characteristics: " + readableCharacteristics.size());
                handler.postDelayed(() -> gatt.readCharacteristic(readableCharacteristics.get(0)), 100);
            } else {
                runOnUiThread(() -> characteristicValuesText.setText("No readable characteristics found."));
            }
        }
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d("BLE", "Reading characteristic: " + characteristic.getUuid() + ", Status: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                String value;
                if (characteristic.getUuid().equals(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))) {
                    value = characteristic.getValue() != null ? String.valueOf(characteristic.getValue()[0]) + "%" : "No data";
                } else {
                    value = characteristic.getValue() != null ? new String(characteristic.getValue(), java.nio.charset.StandardCharsets.UTF_8) : "No data";
                }
                characteristicValues.append("Characteristic UUID: ").append(characteristic.getUuid()).append("\nValue: ").append(value).append("\n\n");
                runOnUiThread(() -> {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    statusText.setText("Read from " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()) + ": " + value);
                    characteristicValuesText.setText(characteristicValues.toString());
                    Toast.makeText(MainActivity.this, "Read: " + value, Toast.LENGTH_SHORT).show();
                });
            } else {
                characteristicValues.append("Characteristic UUID: ").append(characteristic.getUuid()).append("\nError reading: ").append(status).append("\n\n");
                runOnUiThread(() -> {
                    characteristicValuesText.setText(characteristicValues.toString());
                    Toast.makeText(MainActivity.this, "Error reading data: " + status, Toast.LENGTH_SHORT).show();
                });
            }

            currentReadIndex++;
            if (currentReadIndex < readableCharacteristics.size() && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed(() -> gatt.readCharacteristic(readableCharacteristics.get(currentReadIndex)), 100);
            } else {
                runOnUiThread(() -> characteristicValuesText.setText(characteristicValues.toString()));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Data written successfully", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error writing data: " + status, Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String value = characteristic.getValue() != null ? new String(characteristic.getValue(), java.nio.charset.StandardCharsets.UTF_8) : "No data";
            characteristicValues.append("Notification from UUID: ").append(characteristic.getUuid()).append("\nValue: ").append(value).append("\n\n");
            runOnUiThread(() -> {
                statusText.setText("Received notification: " + value);
                characteristicValuesText.setText(characteristicValues.toString());
                Toast.makeText(MainActivity.this, "Received: " + value, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d("BLE", "MTU changed to: " + mtu);
            bleMtu = mtu;
        }
    };
    // -------------- BLE setup end --------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            stopBleScan();
        }
        if (socket != null && socket.isConnected()) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e("Bluetooth", "Error closing socket", e);
            }
            socket = null;
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        unregisterReceiver(bluetoothReceiver);
    }
}