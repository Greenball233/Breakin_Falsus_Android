import math
import socket
import pydirectinput
import time
from pynput import keyboard

# 配置
UDP_IP = "0.0.0.0"
UDP_PORT = 5005
SENSITIVITY = 1
SCREEN_WIDTH = 1080
ANGLE_DEAD_ZONE = 0.0005
 
# 状态变量
current_x = SCREEN_WIDTH // 2
is_controlling = True 

# 考虑替换为int,但是pydirectinput不支持（或者我没看懂   
keys_table = [
    "shift", "a", "s", "d", "f", "space"
]
current_keys_state = [0] * len(keys_table)

# 禁用 pydirectinput 默认延迟
pydirectinput.PAUSE = 0

def on_press(key):
    global is_controlling, current_x
    try:
        # 监听空格键
        if key == keyboard.Key.backspace:
            is_controlling = not is_controlling
            status = "【已开启】" if is_controlling else "【已释放】"
            print(f"\n[{time.strftime('%H:%M:%S')}] 状态变更: {status}")
            
            # 重新开启时，从鼠标当前实际位置开始接管，防止跳变
            if is_controlling:
                current_x = pydirectinput.position()[0]
    except AttributeError:
        pass

# 启动非阻塞按键监听器 
listener = keyboard.Listener(on_press=on_press)
listener.start()

# 设置网络
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))
sock.settimeout(0.1)  # 避免 recvfrom 永久阻塞

print(f"[{time.strftime('%H:%M:%S')}] 接收端已启动")
print(f"灵敏度: {SENSITIVITY} | 监听端口: {UDP_PORT}")
print("-" * 60)

last_print_time = time.time()
msg_count = 0

try:
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            curr_time = time.time()
            
            raw_msg = data.decode().split('|')
            key_type = str(raw_msg[0])
            key_para = raw_msg[1]

            if is_controlling:
                # 基于角速度的绝对坐标模拟
                print(raw_msg)  # 调试输出原始数据
                if key_type == "A":  # 角速度移动
                    key_para = float(key_para) - 10
                    if abs(key_para) > ANGLE_DEAD_ZONE:
                        current_x -= key_para * SENSITIVITY
                    pydirectinput.moveRel(math.floor(current_x+0.5), 0)

                if key_type == "M":  # 鼠标移动
                    pydirectinput.moveTo(x=math.floor(float(key_para) * SENSITIVITY + 0.5), relative=True)

                if key_type == "K":  # 角速度移动
                    for i,j in enumerate(str(key_para)):
                        if j == '1' and current_keys_state[i] == 0:
                            pydirectinput.keyDown(keys_table[i])
                            current_keys_state[i] = 1
                        elif j == '0' and current_keys_state[i] == 1:
                            pydirectinput.keyUp(keys_table[i])
                            current_keys_state[i] = 0

        except socket.timeout:
            continue
except KeyboardInterrupt:
    print("\n[退出] 正在关闭...")
    listener.stop()