package moe.zl.breakinfalsus.output;

import android.content.Context;

public class BluetoothHidOutputTransport extends OutputTransport {

    private final BluetoothHidManager manager;

    public BluetoothHidOutputTransport(Context context, String deviceAddress) {
        manager = BluetoothHidManager.getInstance(context);
        manager.acquire(deviceAddress);
    }

    @Override
    public void sendKeyboardState(boolean[] keyStates) {
        manager.sendKeyboardState(keyStates);
    }

    @Override
    public void sendAccelerometer(float value) {
        // Bluetooth HID emits motion through relative mouse reports only.
    }

    @Override
    public void sendGyroscope(float value) {
        // Bluetooth HID emits motion through relative mouse reports only.
    }

    @Override
    public void sendMouseMove(int deltaX, int deltaY) {
        manager.sendMouseMove(deltaX, deltaY);
    }

    @Override
    public void sendPauseToggle() {
        manager.sendPauseToggle();
    }

    @Override
    public void sendAccelerometerCalibration(float zeroG) {
        // Calibration is handled on-device before relative reports are generated.
    }

    @Override
    public void close() {
        manager.release();
    }
}
