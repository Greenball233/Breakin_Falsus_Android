# Breakin Falsus

Breakin Falsus is a landscape Android rhythm-game controller written in Java. It turns a phone into a six-key touch controller plus a motion mouse bridge, then forwards input to a PC through UDP or TCP packets, root HID gadget output, or Bluetooth HID device mode.

## Features

- Six-key fullscreen touch input with multi-touch state aggregation
- Keyboard output modes:
  - UDP packet: `K|000000`
  - TCP line packet: `K|000000\n`
  - Root HID keyboard report
  - Bluetooth HID keyboard report
- Mouse input modes:
  - Accelerometer with sensitivity and deadzone
  - Gyroscope Z-axis with sensitivity and deadzone
- Mouse output modes:
  - UDP packet for accelerometer: `A|{float}`
  - UDP packet for gyroscope: `M|{int}`
  - TCP packet for accelerometer / gyroscope with the same payload format
  - Root HID mouse movement report
  - Bluetooth HID relative mouse report
- Landscape-only controller UI
- Collapsible settings panel with animated hide on `Apply`
- Persistent configuration with automatic restore on next launch

## Dependencies

This project intentionally keeps dependencies small:

- `androidx.appcompat:appcompat`
- `com.google.android.material:material`
- `com.github.topjohnwu.libsu:io`

## Input and Output Model

### Keyboard input

The touchscreen is divided into six zones:

1. Left Shift
2. A
3. S
4. D
5. F
6. Space

Touch states are collected from the parent fullscreen control and sent as one combined frame.

### Keyboard output

- UDP format: `K|000000`
- TCP format: `K|000000\n`
  - `0` means released
  - `1` means pressed
  - digits 1 through 6 map to the six touch zones above
- Root HID / Bluetooth HID format:
  - Left Shift is sent as the keyboard modifier bit
  - `A`, `S`, `D`, `F`, `Space` are sent as standard HID keycodes

### Mouse input

- Accelerometer mode uses accelerometer X-axis values
- Gyroscope mode uses gyroscope Z-axis values
- Both modes support sensitivity and deadzone

### Mouse output

- UDP accelerometer format: `A|{Value}`
- UDP gyroscope format: `M|{Value}`
- TCP accelerometer format: `A|{Value}`
- TCP gyroscope format: `M|{Value}`
- Root HID and Bluetooth HID mouse output send relative movement reports

## TCP receiver notes

`main-server.py` now listens on both UDP and TCP using the same message protocol. TCP uses newline-delimited frames, for example:

```text
K|010000
A|0.125
P|1
```

## Building locally

### Windows

```powershell
.\gradlew.bat assembleDebug
```

### macOS / Linux

```bash
./gradlew assembleDebug
```

Output APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

This repository includes a workflow at `.github/workflows/android-debug.yml`.

It will:

- build the debug APK on push and pull request
- support manual runs through `workflow_dispatch`
- upload `app-debug.apk` as a workflow artifact

## Root HID notes

For HID mode, the target Android device must:

- be rooted
- expose writable HID gadget device files such as `/dev/hidg0` and `/dev/hidg1`

The app uses `libsu` `SuFile` output streams to write HID reports.

## Bluetooth HID notes

For `BT_HID` mode, the Android phone acts as a Bluetooth HID combo device.

- Android 9 or newer is recommended for `BluetoothHidDevice`
- the phone must grant `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE`
- the target host should already be paired with the phone
- enter the host MAC address in the new `Bluetooth Host MAC` field before applying the config

## Current assumptions

- Accelerometer UDP sends the raw X-axis float value
- Gyroscope UDP sends the scaled Z-axis integer value
- HID mouse movement uses the same processed delta after sensitivity and deadzone filtering

If your PC-side service expects a different axis, packet timing strategy, or HID path layout, those can be adjusted in the app logic.
