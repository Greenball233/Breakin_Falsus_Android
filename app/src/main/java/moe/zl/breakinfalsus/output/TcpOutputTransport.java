package moe.zl.breakinfalsus.output;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpOutputTransport extends OutputTransport {

    private static final int CONNECT_TIMEOUT_MS = 1500;

    private final String host;
    private final int port;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private Socket socket;
    private BufferedWriter writer;

    public TcpOutputTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void sendKeyboardState(boolean[] keyStates) {
        if (keyStates == null || keyStates.length != 6) {
            return;
        }
        StringBuilder builder = new StringBuilder("K|");
        for (boolean keyState : keyStates) {
            builder.append(keyState ? '1' : '0');
        }
        send(builder.toString());
    }

    @Override
    public void sendAccelerometer(float value) {
        send(String.format(Locale.US, "A|%s", value));
    }

    @Override
    public void sendGyroscope(float value) {
        send(String.format(Locale.US, "M|%s", value));
    }

    @Override
    public void sendGravity(float value) {
        send(String.format(Locale.US, "G|%s", value));
    }

    @Override
    public void sendMouseMove(int deltaX, int deltaY) {
        // TCP mode mirrors the semantic network protocol used by UDP mode.
    }

    @Override
    public void sendPauseToggle() {
        send("P|1");
    }

    @Override
    public void sendAccelerometerCalibration(float zeroG) {
        send(String.format(Locale.US, "AZ|%s", zeroG));
    }

    @Override
    public boolean supportsReset() {
        return true;
    }

    @Override
    public void sendReset() {
        send("RESET|0");
    }

    @Override
    public void close() {
        executorService.shutdownNow();
        closeSocket();
    }

    private void send(String message) {
        if (host == null || host.trim().isEmpty() || port <= 0) {
            return;
        }
        executorService.execute(() -> {
            try {
                ensureConnected();
                if (writer == null) {
                    return;
                }
                writer.write(message);
                writer.write('\n');
                writer.flush();
            } catch (Exception ignored) {
                closeSocket();
            }
        });
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        closeSocket();
        Socket newSocket = new Socket();
        newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket = newSocket;
        writer = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private synchronized void closeSocket() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        writer = null;
        socket = null;
    }
}
