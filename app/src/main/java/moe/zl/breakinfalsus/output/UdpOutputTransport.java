package moe.zl.breakinfalsus.output;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpOutputTransport extends OutputTransport {

    private final String host;
    private final int port;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public UdpOutputTransport(String host, int port) {
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
    public void sendGyroscope(int value) {
        send(String.format(Locale.US, "M|%d", value));
    }

    @Override
    public void sendMouseMove(int deltaX, int deltaY) {
        // UDP mode uses semantic A/M packets, so raw mouse HID deltas are ignored here.
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }

    public void send(String message) {
        if (host == null || host.trim().isEmpty() || port <= 0) {
            return;
        }
        executorService.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(payload, payload.length, InetAddress.getByName(host), port);
                socket.send(packet);
            } catch (Exception ignored) {
                // Intentionally ignored to avoid crashing the controller loop.
            }
        });
    }
}
