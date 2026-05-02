import math
import socket
import threading
import time
from pathlib import Path

from pynput import keyboard, mouse

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
    "sensitivity": 1.0,
    "screen_width": 2880,
    "zoom_level": 2,
    "angle_dead_zone": 0.05,
    "interpolation_sleep": 0.005,
    "accel_interpolation_steps": 3,
    "accel_zero_g": 0.0,
    "accel_filter_alpha": 0.35,
    "accel_target_hysteresis_steps": 1,
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

ACCEL_INTERPOLATION_STEPS = max(
    1,
    int(config.get("accel_interpolation_steps", 3))
)

ACCEL_FILTER_ALPHA = float(config.get("accel_filter_alpha", 0.35))

ACCEL_TARGET_HYSTERESIS_PX = max(
    ACCEL_INTERPOLATION_STEPS,
    int(config.get("accel_target_hysteresis_steps", 1)) * ACCEL_INTERPOLATION_STEPS
)

ACCEL_COEFFICIENT = SCREEN_WIDTH / 9.8
MIDPOINT = SCREEN_WIDTH // ZOOM_LEVEL // 2

is_controlling = True
accel_zero_g = float(config.get("accel_zero_g", 0.0))
accel_filtered_value = 0.0
gyro_remainder = 0.0


# Linux 下 pynput 的鼠标和键盘控制器
mouse_controller = mouse.Controller()
keyboard_controller = keyboard.Controller()


# 原来的键位映射
keys_table = [
    "shift",
    "a",
    "s",
    "d",
    "f",
    "space",
]

current_keys_state = [0] * len(keys_table)


# 鼠标平滑移动相关状态
move_target_x = MIDPOINT
last_queued_target_x = MIDPOINT
move_steps_remaining = 0

move_target_lock = threading.Lock()
move_target_event = threading.Event()
move_stop_event = threading.Event()
server_stop_event = threading.Event()


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def get_key(key_name):
    """
    把字符串按键名转换成 pynput 可用的 Key 或字符。
    """
    special_keys = {
        "shift": keyboard.Key.shift,
        "space": keyboard.Key.space,
        "esc": keyboard.Key.esc,
        "backspace": keyboard.Key.backspace,
        "ctrl": keyboard.Key.ctrl,
        "alt": keyboard.Key.alt,
        "enter": keyboard.Key.enter,
        "tab": keyboard.Key.tab,
    }

    return special_keys.get(key_name, key_name)


def key_down(key_name):
    key = get_key(key_name)
    keyboard_controller.press(key)


def key_up(key_name):
    key = get_key(key_name)
    keyboard_controller.release(key)


def press_key(key_name):
    key = get_key(key_name)
    keyboard_controller.press(key)
    keyboard_controller.release(key)


def get_mouse_x():
    return mouse_controller.position[0]


def move_mouse_rel(dx, dy=0):
    x, y = mouse_controller.position
    mouse_controller.position = (x + dx, y + dy)


def set_accel_zero(new_zero):
    global accel_zero_g, accel_filtered_value, config

    accel_zero_g = float(new_zero)
    accel_filtered_value = 0.0
    config["accel_zero_g"] = accel_zero_g

    save_config(config)

    log(f"Saved accelerometer zero g = {accel_zero_g:.5f}")


def on_press(key):
    global is_controlling

    if key == keyboard.Key.backspace:
        is_controlling = not is_controlling
        log(f"Control {'enabled' if is_controlling else 'disabled'}")


def quantize_accel_target(raw_target_x):
    delta_from_midpoint = raw_target_x - MIDPOINT
    quantized_delta = round(delta_from_midpoint / ACCEL_INTERPOLATION_STEPS) * ACCEL_INTERPOLATION_STEPS
    return MIDPOINT + quantized_delta


def queue_accel_target(raw_value):
    global accel_filtered_value
    global move_target_x
    global move_steps_remaining
    global last_queued_target_x

    adjusted_value = raw_value - accel_zero_g

    accel_filtered_value += (
        adjusted_value - accel_filtered_value
    ) * ACCEL_FILTER_ALPHA

    filtered_value = (
        0.0
        if abs(accel_filtered_value) < ANGLE_DEAD_ZONE
        else accel_filtered_value
    )

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


def move_to_midpoint():
    global move_target_x
    global move_steps_remaining
    global last_queued_target_x

    with move_target_lock:
        move_target_x = MIDPOINT
        last_queued_target_x = MIDPOINT
        move_steps_remaining = ACCEL_INTERPOLATION_STEPS

    move_target_event.set()


def move_worker():
    global move_steps_remaining

    while not move_stop_event.is_set():
        move_target_event.wait(0.05)

        if move_stop_event.is_set():
            break

        if not move_target_event.is_set():
            continue

        with move_target_lock:
            target_x = move_target_x
            steps_remaining = move_steps_remaining

        current_x = get_mouse_x()
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

        move_mouse_rel(step_delta, 0)

        with move_target_lock:
            if move_target_x == target_x:
                move_steps_remaining = max(0, move_steps_remaining - 1)

                if move_steps_remaining == 0:
                    move_target_event.clear()
                    log(f"Reached target x={target_x} with final delta={delta_x}")

        time.sleep(INTERPOLATION_SLEEP)


def send_pause_key():
    press_key("esc")
    log("Sent Esc pause toggle")


def apply_gyro(raw_value):
    global gyro_remainder

    adjusted_value = 0.0 if abs(raw_value) < ANGLE_DEAD_ZONE else raw_value

    total_delta = (-adjusted_value * SENSITIVITY / 2.0) + gyro_remainder
    hid_delta = math.trunc(total_delta)

    gyro_remainder = total_delta - hid_delta

    if hid_delta != 0:
        move_mouse_rel(hid_delta, 0)


def handle_message(message):
    try:
        raw_msg = message.decode(errors="ignore").split("|", 1)
    except AttributeError:
        raw_msg = str(message).split("|", 1)

    if len(raw_msg) != 2:
        return

    key_type = raw_msg[0]
    key_para = raw_msg[1]

    if key_type == "AZ":
        try:
            set_accel_zero(float(key_para))
        except ValueError:
            log(f"Invalid AZ value: {key_para}")
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
            log(f"Invalid accel value: {key_para}")

    elif key_type == "M":
        try:
            apply_gyro(float(key_para))
        except ValueError:
            log(f"Invalid gyro value: {key_para}")

    elif key_type == "K":
        for i, state in enumerate(str(key_para)):
            if i >= len(current_keys_state):
                break

            key_name = keys_table[i]

            if state == "1" and current_keys_state[i] == 0:
                key_down(key_name)
                current_keys_state[i] = 1

            elif state == "0" and current_keys_state[i] == 1:
                key_up(key_name)
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
                        handle_message(line.encode())

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


def release_all_keys():
    for i, state in enumerate(current_keys_state):
        if state == 1:
            try:
                key_up(keys_table[i])
            except Exception:
                pass
            current_keys_state[i] = 0


def main():
    listener = keyboard.Listener(on_press=on_press)
    listener.start()

    move_thread = threading.Thread(
        target=move_worker,
        name="move-worker",
        daemon=True,
    )
    move_thread.start()

    udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_socket.bind((UDP_IP, UDP_PORT))
    udp_socket.settimeout(0.1)

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
        "udp=%s:%d tcp=%s:%d sensitivity=%.3f accel_steps=%d accel_zero=%.5f"
        % (
            UDP_IP,
            UDP_PORT,
            TCP_IP,
            TCP_PORT,
            SENSITIVITY,
            ACCEL_INTERPOLATION_STEPS,
            accel_zero_g,
        )
    )

    try:
        while True:
            try:
                data, addr = udp_socket.recvfrom(1024)
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

        try:
            udp_socket.close()
        except Exception:
            pass

        try:
            tcp_server_socket.close()
        except Exception:
            pass

        move_thread.join(timeout=0.2)
        tcp_server_thread.join(timeout=0.2)

        listener.stop()


if __name__ == "__main__":
    main()