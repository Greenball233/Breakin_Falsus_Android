package moe.zl.breakinfalsus.output;

public abstract class OutputTransport {

    public abstract void sendKeyboardState(boolean[] keyStates);

    public abstract void sendAccelerometer(float value);

    public abstract void sendGyroscope(float value);

    public abstract void sendMouseMove(int deltaX, int deltaY);

    public abstract void sendPauseToggle();

    public abstract void sendAccelerometerCalibration(float zeroG);

    public abstract void close();
}
