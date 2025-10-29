import socket
import threading
import json
import time
from enum import Enum
import vgamepad as vg
from dataclasses import dataclass
from typing import Dict, Optional

class ClientStatus(Enum):
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"

@dataclass
class ClientInfo:
    socket: socket.socket
    address: tuple
    gamepad_id: int
    device_info: Optional[dict]
    status: ClientStatus
    last_heartbeat: float
    virtual_gamepad: vg.VX360Gamepad

class GamePadServer:
    def __init__(self, host='0.0.0.0', port=8888):
        self.host = host
        self.port = port
        self.clients: Dict[int, ClientInfo] = {}
        self.next_gamepad_id = 1
        self.server_socket = None
        self.running = False
        self.lock = threading.Lock()
        
    def start_server(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(5)
        self.running = True
        
        print(f"🎮 GamePad Server started on {self.host}:{self.port}")
        print("Waiting for Android clients to connect...")
        
        # Поток для принятия подключений
        accept_thread = threading.Thread(target=self.accept_connections)
        accept_thread.daemon = True
        accept_thread.start()
        
        # Поток для мониторинга подключений
        monitor_thread = threading.Thread(target=self.monitor_connections)
        monitor_thread.daemon = True
        monitor_thread.start()
        
    def accept_connections(self):
        while self.running:
            try:
                client_socket, client_address = self.server_socket.accept()
                print(f"📱 New connection from {client_address}")
                
                with self.lock:
                    # Создаем виртуальный геймпад для этого клиента
                    gamepad_id = self.next_gamepad_id
                    self.next_gamepad_id += 1
                    
                    virtual_gamepad = vg.VX360Gamepad()
                    
                    # Регистрируем клиента
                    client_info = ClientInfo(
                        socket=client_socket,
                        address=client_address,
                        gamepad_id=gamepad_id,
                        device_info=None,
                        status=ClientStatus.CONNECTED,
                        last_heartbeat=time.time(),
                        virtual_gamepad=virtual_gamepad
                    )
                    
                    self.clients[gamepad_id] = client_info
                
                # Отправляем ID геймпада клиенту
                welcome_msg = {
                    'type': 'welcome',
                    'gamepad_id': gamepad_id,
                    'message': f'Successfully registered as GamePad #{gamepad_id}'
                }
                self.send_to_client(gamepad_id, welcome_msg)
                
                # Запускаем поток для обработки сообщений от клиента
                client_thread = threading.Thread(
                    target=self.handle_client, 
                    args=(gamepad_id,)
                )
                client_thread.daemon = True
                client_thread.start()
                
            except Exception as e:
                if self.running:
                    print(f"❌ Error accepting connection: {e}")
    
    def handle_client(self, gamepad_id: int):
        client = self.clients.get(gamepad_id)
        if not client:
            return
        
        # Устанавливаем таймаут для обнаружения разрыва соединения
        client.socket.settimeout(30.0)
        
        buffer = ""
        try:
            while self.running and client.status == ClientStatus.CONNECTED:
                try:
                    data = client.socket.recv(4096)
                    if not data:
                        print(f"⚠️ Client {gamepad_id} closed connection (no data)")
                        break
                    
                    # Добавляем данные в буфер
                    buffer += data.decode('utf-8', errors='ignore')
                    
                    # Обрабатываем все полные сообщения (разделенные \n)
                    while '\n' in buffer:
                        line, buffer = buffer.split('\n', 1)
                        line = line.strip()
                        if line:
                            try:
                                self.process_client_message(gamepad_id, line)
                            except json.JSONDecodeError as e:
                                print(f"❌ Invalid JSON from client {gamepad_id}: {e}")
                            except Exception as e:
                                print(f"❌ Error processing message from client {gamepad_id}: {e}")
                                
                except socket.timeout:
                    # Таймаут - проверяем heartbeat
                    with self.lock:
                        if gamepad_id in self.clients:
                            if time.time() - client.last_heartbeat > 10:
                                print(f"⏰ Client {gamepad_id} heartbeat timeout")
                                break
                    continue
                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                    print(f"⚠️ Connection error with client {gamepad_id}: {e}")
                    break
                    
        except Exception as e:
            print(f"❌ Error with client {gamepad_id}: {e}")
        finally:
            self.disconnect_client(gamepad_id)
    
    def process_client_message(self, gamepad_id: int, message: str):
        try:
            data = json.loads(message)
            msg_type = data.get('type')
            
            if msg_type == 'heartbeat':
                with self.lock:
                    if gamepad_id in self.clients:
                        self.clients[gamepad_id].last_heartbeat = time.time()
                
            elif msg_type == 'device_info':
                with self.lock:
                    if gamepad_id in self.clients:
                        self.clients[gamepad_id].device_info = data.get('device_data')
                device_data = data.get('device_data', {})
                print(f"📊 Device {gamepad_id} info: {device_data.get('device_name', 'Unknown')}")
                
            elif msg_type == 'gamepad_input':
                self.handle_gamepad_input(gamepad_id, data)
                
            else:
                print(f"📨 Unknown message type from client {gamepad_id}: {msg_type}")
                
        except Exception as e:
            print(f"❌ Error processing message from client {gamepad_id}: {e}")
    
    def handle_gamepad_input(self, gamepad_id: int, data: dict):
        client = self.clients.get(gamepad_id)
        if not client:
            return
            
        try:
            input_data = data.get('input_data', {})
            gamepad = client.virtual_gamepad
            
            # Обработка кнопок
            buttons = input_data.get('buttons', {})
            self.update_buttons(gamepad, buttons)
            
            # Обработка джойстиков
            left_joystick = input_data.get('left_joystick', {})
            right_joystick = input_data.get('right_joystick', {})
            self.update_joysticks(gamepad, left_joystick, right_joystick)
            
            # Обработка триггеров
            left_trigger = input_data.get('left_trigger', 0)
            right_trigger = input_data.get('right_trigger', 0)
            self.update_triggers(gamepad, left_trigger, right_trigger)
            
            # Применяем все изменения
            gamepad.update()
            
            # Логируем активный ввод (опционально, с ограничением частоты)
            active_buttons = [k for k, v in buttons.items() if v is True]
            if active_buttons or abs(left_trigger) > 0.1 or abs(right_trigger) > 0.1:
                print(f"🎯 GamePad {gamepad_id} input: Buttons={active_buttons}, LT={left_trigger:.2f}, RT={right_trigger:.2f}")
                
        except Exception as e:
            print(f"❌ Error processing input from device {gamepad_id}: {e}")
    
    def update_buttons(self, gamepad, buttons: dict):
        # Кнопки ABXY - учитываем явное значение False для отпускания
        if buttons.get('a') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
        elif buttons.get('a') is False or 'a' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
        
        if buttons.get('b') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
        elif buttons.get('b') is False or 'b' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
        
        if buttons.get('x') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_X)
        elif buttons.get('x') is False or 'x' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_X)
        
        if buttons.get('y') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_Y)
        elif buttons.get('y') is False or 'y' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_Y)
        
        # Бамперы
        if buttons.get('lb') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
        elif buttons.get('lb') is False or 'lb' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
        
        if buttons.get('rb') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
        elif buttons.get('rb') is False or 'rb' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
        
        # Кнопки меню
        if buttons.get('menu') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)
        elif buttons.get('menu') is False or 'menu' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)
        
        if buttons.get('view') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK)
        elif buttons.get('view') is False or 'view' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK)
        
        # D-Pad
        if buttons.get('dpad_up') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP)
        elif buttons.get('dpad_up') is False or 'dpad_up' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP)
        
        if buttons.get('dpad_down') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN)
        elif buttons.get('dpad_down') is False or 'dpad_down' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN)
        
        if buttons.get('dpad_left') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
        elif buttons.get('dpad_left') is False or 'dpad_left' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT)
        
        if buttons.get('dpad_right') is True: gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
        elif buttons.get('dpad_right') is False or 'dpad_right' not in buttons: gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT)
    
    def update_joysticks(self, gamepad, left_joystick: dict, right_joystick: dict):
        # Левый джойстик
        left_x = left_joystick.get('x', 0)
        left_y = left_joystick.get('y', 0)
        gamepad.left_joystick_float(x_value_float=left_x, y_value_float=left_y)
        
        # Правый джойстик
        right_x = right_joystick.get('x', 0)
        right_y = right_joystick.get('y', 0)
        gamepad.right_joystick_float(x_value_float=right_x, y_value_float=right_y)
    
    def update_triggers(self, gamepad, left_trigger: float, right_trigger: float):
        gamepad.left_trigger_float(value_float=left_trigger)
        gamepad.right_trigger_float(value_float=right_trigger)
    
    def monitor_connections(self):
        while self.running:
            time.sleep(5)
            current_time = time.time()
            disconnected_clients = []
            
            with self.lock:
                for gamepad_id, client in self.clients.items():
                    if current_time - client.last_heartbeat > 10:  # 10 секунд таймаут
                        disconnected_clients.append(gamepad_id)
            
            for gamepad_id in disconnected_clients:
                print(f"⏰ Client {gamepad_id} timeout, disconnecting...")
                self.disconnect_client(gamepad_id)
    
    def send_to_client(self, gamepad_id: int, message: dict):
        try:
            client = self.clients.get(gamepad_id)
            if client and client.status == ClientStatus.CONNECTED:
                json_message = json.dumps(message) + '\n'
                client.socket.send(json_message.encode())
        except Exception as e:
            print(f"❌ Error sending message to client {gamepad_id}: {e}")
            self.disconnect_client(gamepad_id)
    
    def disconnect_client(self, gamepad_id: int):
        with self.lock:
            if gamepad_id in self.clients:
                client = self.clients[gamepad_id]
                client.status = ClientStatus.DISCONNECTED
                try:
                    client.socket.close()
                except:
                    pass
                
                # Освобождаем виртуальный геймпад
                try:
                    # Reset gamepad before disconnecting
                    client.virtual_gamepad.reset()
                    client.virtual_gamepad.update()
                except:
                    pass
                
                del self.clients[gamepad_id]
                print(f"🔌 Client {gamepad_id} disconnected")
    
    def get_connected_clients(self):
        with self.lock:
            return [client_info for client_info in self.clients.values() 
                   if client_info.status == ClientStatus.CONNECTED]
    
    def stop_server(self):
        self.running = False
        print("🛑 Stopping server...")
        with self.lock:
            for gamepad_id in list(self.clients.keys()):
                self.disconnect_client(gamepad_id)
        if self.server_socket:
            self.server_socket.close()
        print("✅ Server stopped")

def main():
    server = GamePadServer()
    try:
        server.start_server()
        print("Server is running. Press Ctrl+C to stop.")
        
        while server.running:
            try:
                time.sleep(1)
                # Показываем статус каждые 10 секунд
                if int(time.time()) % 10 == 0:
                    connected_count = len(server.get_connected_clients())
                    print(f"📊 Status: {connected_count} client(s) connected")
                    
            except KeyboardInterrupt:
                break
                
    except Exception as e:
        print(f"❌ Server error: {e}")
    finally:
        server.stop_server()

if __name__ == "__main__":
    main()