package moe.zl.breakinfalsus.output;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import java.io.OutputStream;

public class RootHidOutputTransport extends OutputTransport {

    private static final byte[] KEY_CODES = new byte[]{
            0x00,
            0x04,
            0x16,
            0x07,
            0x09,
            0x2c
    };

    private final String keyboardPath;
    private final String mousePath;

    public RootHidOutputTransport(String keyboardPath, String mousePath) {
        this.keyboardPath = keyboardPath;
        this.mousePath = mousePath;
        Shell.getShell();
    }

    @Override
    public void sendKeyboardState(boolean[] keyStates) {
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
        writeReport(keyboardPath, report);
    }

    @Override
    public void sendAccelerometer(float value) {
        // Mouse motion is emitted via sendMouseMove after value filtering.
    }

    @Override
    public void sendGyroscope(int value) {
        // Mouse motion is emitted via sendMouseMove after value filtering.
    }

    @Override
    public void sendMouseMove(int deltaX, int deltaY) {
        byte[] report = new byte[]{
                0x00,
                (byte) deltaX,
                (byte) deltaY,
                0x00
        };
        writeReport(mousePath, report);
    }

    @Override
    public void close() {
        // No long-lived resource to close.
    }

    private void writeReport(String path, byte[] report) {
        try {
            SuFile file = new SuFile(path);
            if (!file.exists()) {
                return;
            }
            OutputStream outputStream = SuFileOutputStream.open(path);
            outputStream.write(report);
            outputStream.flush();
            outputStream.close();
        } catch (Exception ignored) {
            // Keep silent so missing root/HID device does not crash the app.
        }
    }
}
