package moe.zl.breakinfalsus.output;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class BluetoothHidManager {

    private static final byte REPORT_ID_KEYBOARD = 0x01;
    private static final byte REPORT_ID_MOUSE = 0x02;
    private static final byte[] HID_DESCRIPTOR = new byte[]{
            0x05, 0x01,
            0x09, 0x06,
            (byte) 0xA1, 0x01,
            (byte) 0x85, REPORT_ID_KEYBOARD,
            0x05, 0x07,
            0x19, (byte) 0xE0,
            0x29, (byte) 0xE7,
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            (byte) 0x95, 0x08,
            (byte) 0x81, 0x02,
            (byte) 0x95, 0x01,
            0x75, 0x08,
            (byte) 0x81, 0x01,
            (byte) 0x95, 0x06,
            0x75, 0x08,
            0x15, 0x00,
            0x25, 0x65,
            0x05, 0x07,
            0x19, 0x00,
            0x29, 0x65,
            (byte) 0x81, 0x00,
            (byte) 0xC0,
            0x05, 0x01,
            0x09, 0x02,
            (byte) 0xA1, 0x01,
            (byte) 0x85, REPORT_ID_MOUSE,
            0x09, 0x01,
            (byte) 0xA1, 0x00,
            0x05, 0x09,
            0x19, 0x01,
            0x29, 0x03,
            0x15, 0x00,
            0x25, 0x01,
            (byte) 0x95, 0x03,
            0x75, 0x01,
            (byte) 0x81, 0x02,
            (byte) 0x95, 0x01,
            0x75, 0x05,
            (byte) 0x81, 0x01,
            0x05, 0x01,
            0x09, 0x30,
            0x09, 0x31,
            0x09, 0x38,
            0x15, (byte) 0x81,
            0x25, 0x7F,
            0x75, 0x08,
            (byte) 0x95, 0x03,
            (byte) 0x81, 0x06,
            (byte) 0xC0,
            (byte) 0xC0
    };

    private static final byte[] KEY_CODES = new byte[]{
            0x00,
            0x04,
            0x16,
            0x07,
            0x09,
            0x2c
    };

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static BluetoothHidManager instance;

    static synchronized BluetoothHidManager getInstance(Context context) {
        if (instance == null) {
            instance = new BluetoothHidManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                registerAppIfReady();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null;
                connectedDevice = null;
                registered = false;
            }
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            BluetoothHidManager.this.registered = registered;
            if (pluggedDevice != null) {
                connectedDevice = pluggedDevice;
            }
            if (registered) {
                connectIfPossible();
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
            } else if (device != null && device.equals(connectedDevice)) {
                connectedDevice = null;
            }
        }
    };

    private BluetoothHidDevice hidDevice;
    private BluetoothDevice targetDevice;
    private BluetoothDevice connectedDevice;
    private boolean registered;
    private int refCount;

    private BluetoothHidManager(Context appContext) {
        this.appContext = appContext;
    }

    synchronized void acquire(String deviceAddress) {
        refCount++;
        targetDevice = resolveBondedDevice(deviceAddress);
        ensureProxy();
    }

    synchronized void release() {
        refCount = Math.max(0, refCount - 1);
        if (refCount == 0) {
            connectedDevice = null;
            targetDevice = null;
            if (hidDevice != null && hasConnectPermission()) {
                try {
                    hidDevice.unregisterApp();
                } catch (Exception ignored) {
                }
            }
            registered = false;
        }
    }

    synchronized void sendKeyboardState(boolean[] keyStates) {
        if (keyStates == null || keyStates.length != 6) {
            return;
        }
        byte[] report = new byte[8];
        if (keyStates[0]) {
            report[0] = 0x02;
        }
        int keySlot = 2;
        for (int i = 1; i < keyStates.length && keySlot < report.length; i++) {
            if (keyStates[i]) {
                report[keySlot++] = KEY_CODES[i];
            }
        }
        sendReport(REPORT_ID_KEYBOARD, report);
    }

    synchronized void sendMouseMove(int deltaX, int deltaY) {
        byte[] report = new byte[]{
                0x00,
                (byte) deltaX,
                (byte) deltaY,
                0x00
        };
        sendReport(REPORT_ID_MOUSE, report);
    }

    synchronized void sendPauseToggle() {
        byte[] pressed = new byte[8];
        pressed[2] = 0x29;
        sendReport(REPORT_ID_KEYBOARD, pressed);
        sendReport(REPORT_ID_KEYBOARD, new byte[8]);
    }

    private void sendReport(byte reportId, byte[] report) {
        if (!hasConnectPermission()) {
            return;
        }
        registerAppIfReady();
        BluetoothDevice device = connectedDevice != null ? connectedDevice : targetDevice;
        if (hidDevice == null || device == null) {
            Toast.makeText(appContext, "No devices connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            hidDevice.sendReport(device, reportId, report);
        } catch (Exception ignored) {
        }
    }

    private void ensureProxy() {
        if (!hasConnectPermission()) {
            return;
        }
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null || !adapter.isEnabled() || hidDevice != null) {
            registerAppIfReady();
            return;
        }
        adapter.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE);
    }

    private void registerAppIfReady() {
        if (!hasConnectPermission()) {
            return;
        }
        if (hidDevice == null || registered) {
            connectIfPossible();
            return;
        }
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Breakin Falsus",
                "Phone keyboard and mouse bridge",
                "Breakin Falsus",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HID_DESCRIPTOR
        );
        try {
            hidDevice.registerApp(sdp, null, (BluetoothHidDeviceAppQosSettings) null, EXECUTOR, callback);
        } catch (Exception  exception) {
            Toast.makeText(appContext, exception.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void connectIfPossible() {
        if (!hasConnectPermission()) {
            return;
        }
        if (hidDevice == null || !registered || targetDevice == null) {
            return;
        }
        if (connectedDevice != null && connectedDevice.equals(targetDevice)) {
            return;
        }
        try {
            hidDevice.connect(targetDevice);
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice resolveBondedDevice(String deviceAddress) {
        if (!hasConnectPermission()) {
            return null;
        }  
        if (deviceAddress == null || deviceAddress.trim().isEmpty()) {
            return null;
        }
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (deviceAddress.equalsIgnoreCase(device.getAddress())) {
                return device;
            }
        }
        try {
            return adapter.getRemoteDevice(deviceAddress.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager manager = ContextCompat.getSystemService(appContext, BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }
}
