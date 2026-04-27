package moe.zl.breakinfalsus;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

import moe.zl.breakinfalsus.input.SensorMouseController;
import moe.zl.breakinfalsus.input.SixKeyTouchLayout;
import moe.zl.breakinfalsus.output.OutputTransport;
import moe.zl.breakinfalsus.output.RootHidOutputTransport;
import moe.zl.breakinfalsus.output.UdpOutputTransport;

public class MainActivity extends AppCompatActivity implements SixKeyTouchLayout.OnKeyStateChangeListener {

    private static final String MODE_UDP = "UDP";
    private static final String MODE_HID = "ROOT_HID";
    private static final String SENSOR_ACCEL = "ACCEL";
    private static final String SENSOR_GYRO = "GYRO";
    private static final String PREFS_NAME = "controller_prefs";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_KEYBOARD_HID = "keyboard_hid";
    private static final String PREF_MOUSE_HID = "mouse_hid";
    private static final String PREF_KEYBOARD_OUTPUT = "keyboard_output";
    private static final String PREF_MOUSE_OUTPUT = "mouse_output";
    private static final String PREF_SENSOR_MODE = "sensor_mode";
    private static final String PREF_SENSITIVITY = "sensitivity";
    private static final String PREF_DEADZONE = "deadzone";
    private static final String PREF_CHORD_BUFFER = "chord_buffer";
    private static final String PREF_MOTION_LOG_ENABLED = "motion_log_enabled";
    private static final String PREF_PANEL_HIDDEN = "panel_hidden";
    private static final long PANEL_ANIMATION_DURATION_MS = 220L;

    private SixKeyTouchLayout touchPad;
    private SensorMouseController sensorMouseController;
    private TextInputEditText hostInput;
    private TextInputEditText portInput;
    private TextInputEditText keyboardHidInput;
    private TextInputEditText mouseHidInput;
    private Spinner keyboardOutputSpinner;
    private Spinner mouseOutputSpinner;
    private Spinner sensorModeSpinner;
    private View settingsPanel;
    private MaterialButton showSettingsButton;

    private MaterialButton resetButton;
    private Slider sensitivitySlider;
    private Slider deadzoneSlider;
    private Slider chordBufferSlider;
    private TextView statusText;
    private SwitchMaterial motionLogsSwitch;

    private OutputTransport keyboardTransport;
    private OutputTransport mouseTransport;
    private SharedPreferences preferences;
    private String[] outputModes;
    private String[] sensorModes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        setupDropdowns();
        sensorMouseController = new SensorMouseController(
                this,
                new SensorMouseController.MouseSignalListener() {
                    @Override
                    public void onAccelerometerValue(float value, int hidDelta) {
                        if (mouseTransport == null) {
                            return;
                        }
                        mouseTransport.sendAccelerometer(value);
                        mouseTransport.sendMouseMove(hidDelta, 0);
                        updateStatus(String.format(Locale.US, "Accel %.3f -> %d", value, hidDelta));
                    }

                    @Override
                    public void onGyroscopeValue(int value, int hidDelta) {
                        if (mouseTransport == null) {
                            return;
                        }
                        mouseTransport.sendGyroscope(value);
                        mouseTransport.sendMouseMove(hidDelta, 0);
                        updateStatus(String.format(Locale.US, "Gyro %d -> %d", value, hidDelta));
                    }
                }
        );
        setupActions();
        restorePreferences();
        applyConfiguration();
        touchPad.setOnKeyStateChangeListener(this);
    }

    private void bindViews() {
        touchPad = findViewById(R.id.touchPad);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        keyboardHidInput = findViewById(R.id.keyboardHidInput);
        mouseHidInput = findViewById(R.id.mouseHidInput);
        keyboardOutputSpinner = findViewById(R.id.keyboardOutputSpinner);
        mouseOutputSpinner = findViewById(R.id.mouseOutputSpinner);
        sensorModeSpinner = findViewById(R.id.sensorModeSpinner);
        settingsPanel = findViewById(R.id.settingsPanel);
        showSettingsButton = findViewById(R.id.showSettingsButton);
        resetButton = findViewById(R.id.resetButton);
        sensitivitySlider = findViewById(R.id.sensitivitySlider);
        deadzoneSlider = findViewById(R.id.deadzoneSlider);
        chordBufferSlider = findViewById(R.id.chordBufferSlider);
        statusText = findViewById(R.id.statusText);
        motionLogsSwitch = findViewById(R.id.motion_logs);
    }

    private void setupDropdowns() {
        outputModes = new String[]{MODE_UDP, MODE_HID};
        sensorModes = new String[]{SENSOR_GYRO, SENSOR_ACCEL};
        ArrayAdapter<String> outputAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, outputModes);
        outputAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ArrayAdapter<String> sensorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sensorModes);
        sensorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keyboardOutputSpinner.setAdapter(outputAdapter);
        mouseOutputSpinner.setAdapter(outputAdapter);
        sensorModeSpinner.setAdapter(sensorAdapter);
    }

    private void setupActions() {
        MaterialButton applyButton = findViewById(R.id.applyButton);
        applyButton.setOnClickListener(view -> {
            applyConfiguration();
            savePreferences();
            hideSettingsPanel();
        });
        showSettingsButton.setOnClickListener(view -> {
            showSettingsPanel();
        });
        sensitivitySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (sensorMouseController != null) {
                sensorMouseController.setSensitivity(value);
            }
        });
        deadzoneSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (sensorMouseController != null) {
                sensorMouseController.setDeadzone(value);
            }
        });
        chordBufferSlider.addOnChangeListener((slider, value, fromUser) -> {
            touchPad.setChordBufferDp(value);
            if (fromUser) {
                updateStatus(String.format(Locale.US, "Chord buffer %.0fdp", value));
            }
        });
        motionLogsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            touchPad.setMotionLogEnabled(isChecked);
        });
    }

    private void applyConfiguration() {
        closeTransport(keyboardTransport);
        closeTransport(mouseTransport);
        keyboardTransport = buildTransport(getSelectedOutputMode(keyboardOutputSpinner));
        mouseTransport = buildTransport(getSelectedOutputMode(mouseOutputSpinner));
        sensorMouseController.setMode(
                SENSOR_ACCEL.equalsIgnoreCase(getSelectedSensorMode())
                        ? SensorMouseController.Mode.ACCELEROMETER
                        : SensorMouseController.Mode.GYROSCOPE
        );
        sensorMouseController.setSensitivity(sensitivitySlider.getValue());
        sensorMouseController.setDeadzone(deadzoneSlider.getValue());
        touchPad.setChordBufferDp(chordBufferSlider.getValue());
        touchPad.setMotionLogEnabled(motionLogsSwitch.isChecked());
        updateStatus("Configuration applied");
        if (mouseTransport instanceof UdpOutputTransport) {
            resetButton.setVisibility(VISIBLE);
            resetButton.setOnClickListener((View v)->{
                ((UdpOutputTransport) mouseTransport).send("RESET|0");
            });
        } else {
            resetButton.setVisibility(INVISIBLE);
        }
    }

    private void hideSettingsPanel() {
        if (settingsPanel.getVisibility() == VISIBLE) {
            settingsPanel.animate().cancel();
            showSettingsButton.animate().cancel();
            settingsPanel.animate()
                    .alpha(0f)
                    .translationY(-settingsPanel.getHeight() * 0.2f)
                    .setDuration(PANEL_ANIMATION_DURATION_MS)
                    .withEndAction(() -> {
                        settingsPanel.setVisibility(View.GONE);
                        settingsPanel.setAlpha(1f);
                        settingsPanel.setTranslationY(0f);
                        revealSettingsButton();
                    })
                    .start();
        }
    }

    private void showSettingsPanel() {
        if (settingsPanel.getVisibility() != VISIBLE) {
            showSettingsButton.animate().cancel();
            showSettingsButton.setVisibility(View.GONE);
            settingsPanel.animate().cancel();
            settingsPanel.setVisibility(VISIBLE);
            settingsPanel.setAlpha(0f);
            settingsPanel.setTranslationY(-settingsPanel.getHeight() * 0.2f - dp(8));
            settingsPanel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(PANEL_ANIMATION_DURATION_MS)
                    .start();
        }
    }

    private void revealSettingsButton() {
        showSettingsButton.setAlpha(0f);
        showSettingsButton.setScaleX(0.92f);
        showSettingsButton.setScaleY(0.92f);
        showSettingsButton.setVisibility(VISIBLE);
        showSettingsButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PANEL_ANIMATION_DURATION_MS)
                .start();
    }

    private void setPanelVisibilityImmediately(boolean hidden) {
        settingsPanel.animate().cancel();
        showSettingsButton.animate().cancel();
        if (hidden) {
            settingsPanel.setVisibility(View.GONE);
            settingsPanel.setAlpha(1f);
            settingsPanel.setTranslationY(0f);
            showSettingsButton.setVisibility(VISIBLE);
            showSettingsButton.setAlpha(1f);
            showSettingsButton.setScaleX(1f);
            showSettingsButton.setScaleY(1f);
        } else {
            settingsPanel.setVisibility(VISIBLE);
            settingsPanel.setAlpha(1f);
            settingsPanel.setTranslationY(0f);
            showSettingsButton.setVisibility(View.GONE);
        }
    }

    private void restorePreferences() {
        hostInput.setText(preferences.getString(PREF_HOST, "192.168.0.2"));
        portInput.setText(preferences.getString(PREF_PORT, "9527"));
        keyboardHidInput.setText(preferences.getString(PREF_KEYBOARD_HID, "/dev/hidg0"));
        mouseHidInput.setText(preferences.getString(PREF_MOUSE_HID, "/dev/hidg1"));
        sensitivitySlider.setValue(preferences.getFloat(PREF_SENSITIVITY, 20f));
        deadzoneSlider.setValue(preferences.getFloat(PREF_DEADZONE, 0.05f));
        chordBufferSlider.setValue(preferences.getFloat(PREF_CHORD_BUFFER, 4f));
        motionLogsSwitch.setChecked(preferences.getBoolean(PREF_MOTION_LOG_ENABLED, false));
        setSpinnerSelection(keyboardOutputSpinner, outputModes, preferences.getString(PREF_KEYBOARD_OUTPUT, MODE_UDP));
        setSpinnerSelection(mouseOutputSpinner, outputModes, preferences.getString(PREF_MOUSE_OUTPUT, MODE_UDP));
        setSpinnerSelection(sensorModeSpinner, sensorModes, preferences.getString(PREF_SENSOR_MODE, SENSOR_GYRO));
        setPanelVisibilityImmediately(preferences.getBoolean(PREF_PANEL_HIDDEN, false));
    }

    private void savePreferences() {
        preferences.edit()
                .putString(PREF_HOST, getText(hostInput))
                .putString(PREF_PORT, getText(portInput))
                .putString(PREF_KEYBOARD_HID, getText(keyboardHidInput))
                .putString(PREF_MOUSE_HID, getText(mouseHidInput))
                .putString(PREF_KEYBOARD_OUTPUT, getSelectedOutputMode(keyboardOutputSpinner))
                .putString(PREF_MOUSE_OUTPUT, getSelectedOutputMode(mouseOutputSpinner))
                .putString(PREF_SENSOR_MODE, getSelectedSensorMode())
                .putFloat(PREF_SENSITIVITY, sensitivitySlider.getValue())
                .putFloat(PREF_DEADZONE, deadzoneSlider.getValue())
                .putFloat(PREF_CHORD_BUFFER, chordBufferSlider.getValue())
                .putBoolean(PREF_MOTION_LOG_ENABLED, motionLogsSwitch.isChecked())
                .putBoolean(PREF_PANEL_HIDDEN, true)
                .apply();
    }

    private String getSelectedOutputMode(@NonNull Spinner spinner) {
        Object value = spinner.getSelectedItem();
        return value == null ? MODE_UDP : value.toString();
    }

    private String getSelectedSensorMode() {
        Object value = sensorModeSpinner.getSelectedItem();
        return value == null ? SENSOR_GYRO : value.toString();
    }

    private void setSpinnerSelection(@NonNull Spinner spinner, @NonNull String[] values, @NonNull String wanted) {
        for (int i = 0; i < values.length; i++) {
            if (wanted.equalsIgnoreCase(values[i])) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private String getText(@NonNull TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private OutputTransport buildTransport(@NonNull String mode) {
        if (MODE_HID.equalsIgnoreCase(mode)) {
            String keyboardPath = getText(keyboardHidInput).isEmpty() ? "/dev/hidg0" : getText(keyboardHidInput);
            String mousePath = getText(mouseHidInput).isEmpty() ? "/dev/hidg1" : getText(mouseHidInput);
            return new RootHidOutputTransport(keyboardPath, mousePath);
        }
        String host = getText(hostInput);
        String portString = getText(portInput);
        int port = 9527;
        if (!TextUtils.isEmpty(portString)) {
            try {
                port = Integer.parseInt(portString);
            } catch (NumberFormatException ignored) {
                Toast.makeText(this, "Invalid port, fallback to 9527", Toast.LENGTH_SHORT).show();
            }
        }
        return new UdpOutputTransport(host, port);
    }

    private void updateStatus(@NonNull String message) {
        statusText.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorMouseController.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorMouseController.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeTransport(keyboardTransport);
        closeTransport(mouseTransport);
    }

    @Override
    public void onKeyStateChanged(boolean[] keyStates) {
        if (keyboardTransport != null) {
            keyboardTransport.sendKeyboardState(keyStates);
            updateStatus("Keys " + encodeKeys(keyStates));
        }
    }

    private String encodeKeys(boolean[] keyStates) {
        StringBuilder builder = new StringBuilder(keyStates.length);
        for (boolean keyState : keyStates) {
            builder.append(keyState ? '1' : '0');
        }
        return builder.toString();
    }

    private void closeTransport(OutputTransport transport) {
        if (transport != null) {
            transport.close();
        }
    }
}
