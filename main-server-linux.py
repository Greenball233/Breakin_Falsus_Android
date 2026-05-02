import math
import socket
import threading
import time
from pathlib import Path

from evdev import UInput, ecodes as e, AbsInfo
from pynput import keyboard

try:
    import tomllib
except ImportError:
    import tomli as tomllib


CONFIG_PATH = Path("server-config.toml")
DEFAULT_CONFIG = {
    "udp_ip": "0.0.0.0",
    "udp_port": 5005,
    "tcp_ip": "0.0.0.0",
    "tcp_port": 5005,

    "sensitivity": 40.0,
    "screen_width": 2880,
    "zoom_level": 2,
    "angle_dead_zone": 0.05,

    "interpolation_sleep": 0.005,
    "accel_interpolation_steps": 3,
    "accel_zero_g": 0.0,
    "accel_filter_alpha": 0.35,
    "accel_target_hysteresis_steps": 1,

    # Linux 额外配置
    # false：推荐，使用相对移动，适合游戏/raw input，避免一直向右移动
    # true ：尽量模拟 Windows 原版的“按屏幕位置移动到目标 x”，但 Linux 下不推荐
    "use_absolute_accel": False,

    # 加速度计相对移动灵敏度。只在 use_absolute_accel=false 时使用
    "accel_relative_sensitivity": 1.0,

    # 限制单次鼠标相对移动，防止异常数据导致甩飞
    "max_mouse_delta": 80,

    # 禁用加速度计自动回中。
    # 某些游戏里如果启用“回中/绝对目标”逻辑，容易不断补偿导致持续向右/向左
    "disable_accel_recenter": True,

    # 是否通过 uinput 输入绝对坐标 (EV_ABS)
    "use_absolute_uinput": True,

    # 是否启用本机 Backspace 热键切换控制
    # 在无桌面、Wayland、权限不足时 pynput 可能不可用，可以关掉
    "enable_hotkey_listener": True,
}


def load_config():
    config = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        with CONFIG_PATH.open("rb") as config_file:
            loaded = tomllib.load(config_file)
        config.update(loaded)
    return config


def save_config(config):
    ordered_keys = list(DEFAULT_CONFIG.keys())
    ordered_keys.extend(key for key in config.keys() if key not in ordered_keys)

    lines = []
    for key in ordered_keys:
        value = config[key]
        if isinstance(value, str):
            encoded = value.replace("\\", "\\\\").replace('"', '\\"')
            lines.append(f'{key} = "{encoded}"')
        elif isinstance(value, bool):
            lines.append(f"{key} = {'true' if value else 'false'}")
        else:
            lines.append(f"{key} = {value}")

    CONFIG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


config = load_config()
if not CONFIG_PATH.exists():
    save_config(config)

UDP_IP = config["udp_ip"]
UDP_PORT = int(config["udp_port"])
TCP_IP = config.get("tcp_ip", UDP_IP)
TCP_PORT = int(config.get("tcp_port", UDP_PORT))

SENSITIVITY = float(config["sensitivity"])
SCREEN_WIDTH = int(config["screen_width"])
ZOOM_LEVEL = int(config["zoom_level"])
ANGLE_DEAD_ZONE = float(config["angle_dead_zone"])

INTERPOLATION_SLEEP = float(config["interpolation_sleep"])
ACCEL_INTERPOLATION_STEPS = max(1, int(config.get("accel_interpolation_steps", 3)))
ACCEL_FILTER_ALPHA = float(config.get("accel_filter_alpha", 0.35))
ACCEL_TARGET_HYSTERESIS_PX = max(
    ACCEL_INTERPOLATION_STEPS,
    int(config.get("accel_target_hysteresis_steps", 1)) * ACCEL_INTERPOLATION_STEPS
)

USE_ABSOLUTE_ACCEL = bool(config.get("use_absolute_accel", False))
ACCEL_RELATIVE_SENSITIVITY = float(config.get("accel_relative_sensitivity", 1.0))
MAX_MOUSE_DELTA = max(1, int(config.get("max_mouse_delta", 80)))
DISABLE_ACCEL_RECENTER = bool(config.get("disable_accel_recenter", True))
USE_ABSOLUTE_UINPUT = bool(config.get("use_absolute_uinput", False))
ENABLE_HOTKEY_LISTENER = bool(config.get("enable_hotkey_listener", True))

if USE_ABSOLUTE_UINPUT:
    USE_ABSOLUTE_ACCEL = True
    DISABLE_ACCEL_RECENTER = False

ACCEL_COEFFICIENT = SCREEN_WIDTH / 9.8
MIDPOINT = SCREEN_WIDTH // ZOOM_LEVEL // 2

is_controlling = True
accel_zero_g = float(config.get("accel_zero_g", 0.0))
accel_filtered_value = 0.0
gyro_remainder = 0.0
accel_relative_remainder = 0.0

keys_table = [
    "q", "a", "s", "d", "f", "space"
]
current_keys_state = [0] * len(keys_table)

move_target_x = MIDPOINT
last_queued_target_x = MIDPOINT
virtual_current_x = MIDPOINT
move_steps_remaining = 0

move_target_lock = threading.Lock()
move_target_event = threading.Event()
move_stop_event = threading.Event()
server_stop_event = threading.Event()


# -----------------------------
# Linux uinput 部分
# -----------------------------

KEY_MAP = {
    "q": e.KEY_Q,
    "a": e.KEY_A,
    "s": e.KEY_S,
    "d": e.KEY_D,
    "f": e.KEY_F,
    "space": e.KEY_SPACE,
    "esc": e.KEY_ESC,
}


capabilities = {
    e.EV_KEY: [
        e.BTN_LEFT,
        e.BTN_RIGHT,
        e.BTN_MIDDLE,

        e.KEY_Q,
        e.KEY_A,
        e.KEY_S,
        e.KEY_D,
        e.KEY_F,
        e.KEY_SPACE,
        e.KEY_ESC,
    ],
}

if USE_ABSOLUTE_UINPUT:
    capabilities[e.EV_KEY].append(e.BTN_TOUCH)
    capabilities[e.EV_ABS] = [
        (e.ABS_X, AbsInfo(value=0, min=0, max=SCREEN_WIDTH, fuzz=0, flat=0, resolution=0)),
        # Assume a standard full HD height max, or something generic
        (e.ABS_Y, AbsInfo(value=0, min=0, max=SCREEN_WIDTH, fuzz=0, flat=0, resolution=0)),
    ]
else:
    capabilities[e.EV_REL] = [
        e.REL_X,
        e.REL_Y,
        e.REL_WHEEL,
    ]

ui = UInput(capabilities, name="phone-linux-uinput-controller", version=0x3)


def clamp_mouse_delta(value):
    if value > MAX_MOUSE_DELTA:
        return MAX_MOUSE_DELTA
    if value < -MAX_MOUSE_DELTA:
        return -MAX_MOUSE_DELTA
    return int(value)


abs_mouse_x = MIDPOINT
abs_mouse_y = SCREEN_WIDTH // 2

def mouse_move_rel(dx, dy=0):
    global abs_mouse_x, abs_mouse_y
    dx = clamp_mouse_delta(dx)
    dy = clamp_mouse_delta(dy)

    if dx == 0 and dy == 0:
        return

    if USE_ABSOLUTE_UINPUT:
        abs_mouse_x = max(0, min(SCREEN_WIDTH, abs_mouse_x + dx))
        abs_mouse_y = max(0, min(SCREEN_WIDTH, abs_mouse_y + dy))
        ui.write(e.EV_ABS, e.ABS_X, abs_mouse_x)
        ui.write(e.EV_ABS, e.ABS_Y, abs_mouse_y)
    else:
        if dx != 0:
            ui.write(e.EV_REL, e.REL_X, dx)
        if dy != 0:
            ui.write(e.EV_REL, e.REL_Y, dy)
    ui.syn()


def key_down(key_name):
    code = KEY_MAP.get(key_name)
    if code is None:
        return
    ui.write(e.EV_KEY, code, 1)
    ui.syn()


def key_up(key_name):
    code = KEY_MAP.get(key_name)
    if code is None:
        return
    ui.write(e.EV_KEY, code, 0)
    ui.syn()


def key_press(key_name):
    code = KEY_MAP.get(key_name)
    if code is None:
        return
    ui.write(e.EV_KEY, code, 1)
    ui.syn()
    time.sleep(0.01)
    ui.write(e.EV_KEY, code, 0)
    ui.syn()


def release_all_keys():
    for i, state in enumerate(current_keys_state):
        if state:
            key_up(keys_table[i])
            current_keys_state[i] = 0


# -----------------------------
# 通用逻辑
# -----------------------------

def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def set_accel_zero(new_zero):
    global accel_zero_g, accel_filtered_value, accel_relative_remainder, config

    accel_zero_g = float(new_zero)
    accel_filtered_value = 0.0
    accel_relative_remainder = 0.0

    config["accel_zero_g"] = accel_zero_g
    save_config(config)

    log(f"Saved accelerometer zero g = {accel_zero_g:.5f}")


def on_press(key):
    global is_controlling

    try:
        if key == keyboard.Key.backspace:
            is_controlling = not is_controlling
            if not is_controlling:
                release_all_keys()
            log(f"Control {'enabled' if is_controlling else 'disabled'}")
    except AttributeError:
        pass


def quantize_accel_target(raw_target_x):
    delta_from_midpoint = raw_target_x - MIDPOINT
    quantized_delta = round(delta_from_midpoint / ACCEL_INTERPOLATION_STEPS) * ACCEL_INTERPOLATION_STEPS
    return MIDPOINT + quantized_delta


def queue_accel_target_absolute(raw_value):
    """
    尽量保留 Windows 版的绝对目标位置逻辑。

    注意：
    Linux uinput 相对鼠标设备通常无法可靠读取游戏内真实光标位置，
    尤其是全屏游戏/raw input。
    所以这里使用 virtual_current_x 维护一个虚拟当前位置。
    这比读 X11 光标更底层，但在游戏中仍不一定完全一致。
    """
    global accel_filtered_value
    global move_target_x, move_steps_remaining, last_queued_target_x

    adjusted_value = raw_value - accel_zero_g
    accel_filtered_value += (adjusted_value - accel_filtered_value) * ACCEL_FILTER_ALPHA

    filtered_value = 0.0 if abs(accel_filtered_value) < ANGLE_DEAD_ZONE else accel_filtered_value
    raw_target_x = math.floor(filtered_value * ACCEL_COEFFICIENT + MIDPOINT)
    target_x = quantize_accel_target(raw_target_x)

    if abs(target_x - MIDPOINT) <= ACCEL_TARGET_HYSTERESIS_PX:
        target_x = MIDPOINT

    with move_target_lock:
        if target_x == last_queued_target_x and move_steps_remaining > 0:
            return
        if abs(target_x - move_target_x) < ACCEL_TARGET_HYSTERESIS_PX and target_x != MIDPOINT:
            return

        move_target_x = target_x
        last_queued_target_x = target_x
        move_steps_remaining = ACCEL_INTERPOLATION_STEPS

    move_target_event.set()


def queue_accel_target_relative(raw_value):
    """
    Linux 推荐模式：
    把加速度计输入转成相对鼠标移动，而不是追踪绝对屏幕坐标。

    这样更适合游戏，尤其是 raw input/FPS 游戏。
    也可以避免由于无法读取真实光标位置造成的持续向右/向左移动。
    """
    global accel_filtered_value, accel_relative_remainder

    adjusted_value = raw_value - accel_zero_g
    accel_filtered_value += (adjusted_value - accel_filtered_value) * ACCEL_FILTER_ALPHA

    if abs(accel_filtered_value) < ANGLE_DEAD_ZONE:
        # 死区内不要继续积累小数，否则可能慢慢漂移
        accel_relative_remainder = 0.0
        return

    # 保持和 Windows 原版类似的比例基础，但变成相对量。
    # 除以 ACCEL_INTERPOLATION_STEPS 是为了避免单包移动过大。
    total_delta = (
        accel_filtered_value
        * ACCEL_COEFFICIENT
        * ACCEL_RELATIVE_SENSITIVITY
        / max(1, ACCEL_INTERPOLATION_STEPS)
    ) + accel_relative_remainder

    hid_delta = math.trunc(total_delta)
    accel_relative_remainder = total_delta - hid_delta

    if hid_delta != 0:
        mouse_move_rel(hid_delta, 0)


def queue_accel_target(raw_value):
    if USE_ABSOLUTE_ACCEL:
        queue_accel_target_absolute(raw_value)
    else:
        queue_accel_target_relative(raw_value)


def move_to_midpoint():
    """
    RESET 行为。

    Windows 原版会移动到屏幕中点。
    Linux 推荐模式下不做鼠标回中，只清空状态，避免游戏里持续补偿漂移。
    """
    global move_target_x, move_steps_remaining, last_queued_target_x
    global virtual_current_x, accel_filtered_value, accel_relative_remainder

    accel_filtered_value = 0.0
    accel_relative_remainder = 0.0

    if not USE_ABSOLUTE_ACCEL or DISABLE_ACCEL_RECENTER:
        with move_target_lock:
            move_target_x = MIDPOINT
            last_queued_target_x = MIDPOINT
            virtual_current_x = MIDPOINT
            move_steps_remaining = 0
            move_target_event.clear()
        return

    with move_target_lock:
        move_target_x = MIDPOINT
        last_queued_target_x = MIDPOINT
        move_steps_remaining = ACCEL_INTERPOLATION_STEPS

    move_target_event.set()


def move_worker():
    """
    仅用于 use_absolute_accel=true 的模式。

    因为 uinput 注入的是相对位移，不依赖 X11 光标位置。
    这里用 virtual_current_x 估计当前位置。
    """
    global move_steps_remaining, virtual_current_x

    while not move_stop_event.is_set():
        move_target_event.wait(0.05)

        if move_stop_event.is_set():
            break

        if not move_target_event.is_set():
            continue

        if not USE_ABSOLUTE_ACCEL:
            move_target_event.clear()
            continue

        with move_target_lock:
            target_x = move_target_x
            steps_remaining = move_steps_remaining
            current_x = abs_mouse_x if USE_ABSOLUTE_UINPUT else virtual_current_x

        delta_x = int(round(target_x - current_x))

        if delta_x == 0 or steps_remaining <= 0:
            with move_target_lock:
                if move_target_x == target_x:
                    move_steps_remaining = 0
                    move_target_event.clear()
            continue

        step_delta = int(round(delta_x / steps_remaining))
        if step_delta == 0:
            step_delta = 1 if delta_x > 0 else -1

        step_delta = clamp_mouse_delta(step_delta)
        mouse_move_rel(step_delta, 0)

        with move_target_lock:
            if move_target_x == target_x:
                if not USE_ABSOLUTE_UINPUT:
                    virtual_current_x += step_delta
                move_steps_remaining = max(0, move_steps_remaining - 1)

                if move_steps_remaining == 0:
                    move_target_event.clear()
                    log(f"Reached virtual target x={target_x} virtual_current_x={virtual_current_x}")

        time.sleep(INTERPOLATION_SLEEP)


def send_pause_key():
    key_press("esc")
    log("Sent Esc pause toggle")


def apply_gyro(raw_value):
    global gyro_remainder

    adjusted_value = 0.0 if abs(raw_value) < ANGLE_DEAD_ZONE else raw_value

    if adjusted_value == 0.0:
        gyro_remainder = 0.0
        return

    total_delta = (-adjusted_value * SENSITIVITY / 2.0) + gyro_remainder
    hid_delta = math.trunc(total_delta)
    gyro_remainder = total_delta - hid_delta

    if hid_delta != 0:
        mouse_move_rel(hid_delta, 0)


def handle_message(message):
    try:
        if isinstance(message, bytes):
            text = message.decode(errors="ignore")
        else:
            text = str(message)
    except Exception:
        return

    raw_msg = text.split("|", 1)
    if len(raw_msg) != 2:
        return

    key_type = raw_msg[0]
    key_para = raw_msg[1]

    if key_type == "AZ":
        try:
            set_accel_zero(float(key_para))
        except ValueError:
            pass
        return

    if key_type == "P":
        send_pause_key()
        return

    if not is_controlling:
        return

    if key_type == "RESET":
        move_to_midpoint()

    elif key_type == "A":
        try:
            queue_accel_target(float(key_para))
        except ValueError:
            pass

    elif key_type == "G":
        try:
            queue_accel_target(float(key_para))
        except ValueError:
            pass

    elif key_type == "M":
        try:
            apply_gyro(float(key_para))
        except ValueError:
            pass

    elif key_type == "K":
        for i, state in enumerate(str(key_para)):
            if i >= len(current_keys_state):
                break

            if state == "1" and current_keys_state[i] == 0:
                key_down(keys_table[i])
                current_keys_state[i] = 1

            elif state == "0" and current_keys_state[i] == 1:
                key_up(keys_table[i])
                current_keys_state[i] = 0


def tcp_client_worker(client_socket, client_addr):
    log(f"TCP client connected {client_addr[0]}:{client_addr[1]}")
    buffer = ""

    try:
        client_socket.settimeout(0.1)

        while not server_stop_event.is_set():
            try:
                chunk = client_socket.recv(1024)
                if not chunk:
                    break

                buffer += chunk.decode(errors="ignore")

                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line:
                        handle_message(line)

            except socket.timeout:
                continue

    except OSError:
        pass

    finally:
        client_socket.close()
        log(f"TCP client disconnected {client_addr[0]}:{client_addr[1]}")


def tcp_server_worker(server_socket):
    log(f"TCP receiver listening on {TCP_IP}:{TCP_PORT}")
    server_socket.settimeout(0.1)

    while not server_stop_event.is_set():
        try:
            client_socket, client_addr = server_socket.accept()
            worker = threading.Thread(
                target=tcp_client_worker,
                args=(client_socket, client_addr),
                name=f"tcp-client-{client_addr[0]}:{client_addr[1]}",
                daemon=True,
            )
            worker.start()

        except socket.timeout:
            continue

        except OSError:
            break


listener = None

if ENABLE_HOTKEY_LISTENER:
    try:
        listener = keyboard.Listener(on_press=on_press)
        listener.start()
        log("Hotkey listener started, press Backspace to toggle control")
    except Exception as ex:
        log(f"Hotkey listener failed: {ex}")


move_thread = threading.Thread(target=move_worker, name="move-worker", daemon=True)
move_thread.start()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))
sock.settimeout(0.1)

tcp_server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp_server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tcp_server_socket.bind((TCP_IP, TCP_PORT))
tcp_server_socket.listen()

tcp_server_thread = threading.Thread(
    target=tcp_server_worker,
    args=(tcp_server_socket,),
    name="tcp-server",
    daemon=True,
)
tcp_server_thread.start()

log("Receiver started")
log(
    "udp=%s:%d tcp=%s:%d sensitivity=%.3f accel_steps=%d accel_zero=%.5f use_absolute_accel=%s"
    % (
        UDP_IP,
        UDP_PORT,
        TCP_IP,
        TCP_PORT,
        SENSITIVITY,
        ACCEL_INTERPOLATION_STEPS,
        accel_zero_g,
        USE_ABSOLUTE_ACCEL,
    )
)

try:
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            handle_message(data)

        except socket.timeout:
            continue

except KeyboardInterrupt:
    log("Shutting down")

finally:
    server_stop_event.set()
    move_stop_event.set()
    move_target_event.set()

    release_all_keys()

    move_thread.join(timeout=0.2)

    try:
        tcp_server_socket.close()
    except OSError:
        pass

    tcp_server_thread.join(timeout=0.2)

    if listener is not None:
        listener.stop()

    try:
        sock.close()
    except OSError:
        pass

    try:
        ui.close()
    except Exception:
        pass
