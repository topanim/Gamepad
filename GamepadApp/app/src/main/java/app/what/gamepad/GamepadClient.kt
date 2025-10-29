package app.what.gamepad

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket
import kotlin.math.abs

class GamePadClient(private val context: Context) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var heartbeatJob: Job? = null
    private var streamingJob: Job? = null
    private var isConnected = false
    private var gamepadId = -1
    private var onStatusUpdate: ((String, Int?) -> Unit)? = null
    private var onDisconnectCallback: (() -> Unit)? = null

    // Корутинный scope для сетевых операций
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Состояние контроллера
    @Volatile
    private var leftStickX: Float = 0f
    @Volatile
    private var leftStickY: Float = 0f
    @Volatile
    private var rightStickX: Float = 0f
    @Volatile
    private var rightStickY: Float = 0f
    @Volatile
    private var leftTrigger: Float = 0f
    @Volatile
    private var rightTrigger: Float = 0f
    private val pressedButtons = mutableSetOf<String>()
    private val stateLock = Any()
    private val writerLock = Any()
    private val lastSentState = mutableMapOf<String, Any>()
    private var lastSendTime = 0L

    suspend fun connect(serverIP: String, port: Int, onStatusUpdate: (String, Int?) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                this@GamePadClient.onStatusUpdate = onStatusUpdate
                socket = Socket(serverIP, port)
                socket?.soTimeout = 30000 // 30 секунд таймаут
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                writer = PrintWriter(OutputStreamWriter(outputStream), true)
                reader = BufferedReader(InputStreamReader(inputStream))
                isConnected = true

                onStatusUpdate("Connected", null)

                // Получаем welcome сообщение
                readWelcomeMessage(onStatusUpdate)

                // Отправляем информацию об устройстве
                sendDeviceInfo()

                // Запускаем heartbeat
                startHeartbeat()

                // Запускаем потоковую передачу состояния
                startStreaming()

                // Слушаем входящие сообщения
                listenToServer()

            } catch (e: Exception) {
                onStatusUpdate("Connection failed: ${e.message}", null)
                disconnect()
            }
        }
    }

    fun setOnDisconnectCallback(callback: () -> Unit) {
        onDisconnectCallback = callback
    }

    private fun readWelcomeMessage(onStatusUpdate: (String, Int?) -> Unit) {
        try {
            val message = reader?.readLine()
            if (!message.isNullOrEmpty()) {
                val json = JSONObject(message)
                if (json.getString("type") == "welcome") {
                    gamepadId = json.getInt("gamepad_id")
                    onStatusUpdate("Connected as GamePad $gamepadId", gamepadId)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendDeviceInfo() {
        val deviceInfo = JSONObject().apply {
            put("type", "device_info")
            put("device_data", JSONObject().apply {
                put("device_model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("device_name", getDeviceName())
            })
        }
        sendMessage(deviceInfo.toString())
    }

    private fun getDeviceName(): String {
        return Build.MANUFACTURER + " " + Build.MODEL
    }

    // Методы для отправки ввода (вызываются из UI, но выполняют сетевые операции в фоновом потоке)

    fun sendLeftStick(x: Float, y: Float) {
        leftStickX = x
        leftStickY = y
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendRightStick(x: Float, y: Float) {
        rightStickX = x
        rightStickY = y
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendLeftTrigger(value: Float) {
        leftTrigger = value
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendRightTrigger(value: Float) {
        rightTrigger = value
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendButtonPress(button: String) {
        synchronized(stateLock) {
            pressedButtons.add(button)
        }
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendButtonRelease(button: String) {
        synchronized(stateLock) {
            pressedButtons.remove(button)
        }
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendDPadInput(direction: String) {
        synchronized(stateLock) {
            // Сначала отпускаем все направления D-Pad
            pressedButtons.remove("dpad_up")
            pressedButtons.remove("dpad_down")
            pressedButtons.remove("dpad_left")
            pressedButtons.remove("dpad_right")

            // Нажимаем нужное направление
            when (direction) {
                "up" -> pressedButtons.add("dpad_up")
                "down" -> pressedButtons.add("dpad_down")
                "left" -> pressedButtons.add("dpad_left")
                "right" -> pressedButtons.add("dpad_right")
            }
        }
        networkScope.launch {
            sendGamePadInput()
        }
    }

    fun sendDPadRelease() {
        synchronized(stateLock) {
            pressedButtons.remove("dpad_up")
            pressedButtons.remove("dpad_down")
            pressedButtons.remove("dpad_left")
            pressedButtons.remove("dpad_right")
        }
        networkScope.launch {
            sendGamePadInput()
        }
    }

    private suspend fun sendGamePadInput() {
        // Копируем состояние в локальные переменные для безопасного доступа
        val currentLeftStickX = leftStickX
        val currentLeftStickY = leftStickY
        val currentRightStickX = rightStickX
        val currentRightStickY = rightStickY
        val currentLeftTrigger = leftTrigger
        val currentRightTrigger = rightTrigger
        
        val currentPressedButtons = synchronized(stateLock) {
            pressedButtons.toSet() // Создаем копию для безопасного доступа
        }
        
        val inputData = JSONObject().apply {
            // Джойстики
            put("left_joystick", JSONObject().apply {
                put("x", currentLeftStickX)
                put("y", currentLeftStickY)
            })
            put("right_joystick", JSONObject().apply {
                put("x", currentRightStickX)
                put("y", currentRightStickY)
            })

            // Триггеры
            put("left_trigger", currentLeftTrigger)
            put("right_trigger", currentRightTrigger)

            // Кнопки - отправляем все кнопки с их состояниями
            val buttons = JSONObject()
            // Отправляем нажатые кнопки как true
            currentPressedButtons.forEach { button ->
                buttons.put(button, true)
            }
            put("buttons", buttons)
        }

        val message = JSONObject().apply {
            put("type", "gamepad_input")
            put("input_data", inputData)
        }

        sendMessage(message.toString())
        lastSendTime = System.currentTimeMillis()
    }

    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                try {
                    val heartbeat = JSONObject().apply {
                        put("type", "heartbeat")
                        put("timestamp", System.currentTimeMillis())
                    }
                    sendMessage(heartbeat.toString())
                    delay(3000) // Каждые 3 секунды
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun startStreaming() {
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                try {
                    // Проверяем, есть ли активные элементы управления
                    val hasActiveInput = synchronized(stateLock) {
                        pressedButtons.isNotEmpty()
                    } || abs(leftStickX) > 0.01f || abs(leftStickY) > 0.01f ||
                            abs(rightStickX) > 0.01f || abs(rightStickY) > 0.01f ||
                            leftTrigger > 0.01f || rightTrigger > 0.01f
                    
                    // Если есть активный ввод, отправляем состояние каждые 50мс
                    // Если нет активного ввода, отправляем каждые 200мс для синхронизации
                    if (hasActiveInput) {
                        sendGamePadInput()
                        delay(50) // 20 FPS для активного ввода
                    } else {
                        delay(200) // 5 FPS для неактивного состояния
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun listenToServer() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                try {
                    val message = reader?.readLine()
                    if (message == null) {
                        // Соединение закрыто сервером
                        break
                    }
                    processServerMessage(message)
                } catch (e: java.net.SocketTimeoutException) {
                    // Таймаут - продолжаем слушать
                    continue
                } catch (e: Exception) {
                    // Другие ошибки означают разрыв соединения
                    break
                }
            }
            // Соединение разорвано
            disconnect()
        }
    }

    private fun processServerMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "vibration" -> {
                    handleVibrationCommand(json)
                }
                "connection_info" -> {
                    // Обновление информации о подключении
                    gamepadId = json.getInt("gamepad_id")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleVibrationCommand(json: JSONObject) {
        // Здесь можно реализовать вибрацию устройства
        // val leftMotor = json.getDouble("left_motor")
        // val rightMotor = json.getDouble("right_motor")
    }

    private fun sendMessage(message: String) {
        synchronized(writerLock) {
            try {
                writer?.println(message)
                writer?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
            }
        }
    }

    fun disconnect() {
        val wasConnected = isConnected
        isConnected = false
        heartbeatJob?.cancel()
        streamingJob?.cancel()
        networkScope.cancel()
        
        // Очищаем состояние
        synchronized(stateLock) {
            pressedButtons.clear()
        }
        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f
        leftTrigger = 0f
        rightTrigger = 0f
        
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        socket = null
        outputStream = null
        inputStream = null
        writer = null
        reader = null
        
        // Уведомляем UI об отключении, если оно было неожиданным
        if (wasConnected) {
            onStatusUpdate?.invoke("Disconnected", null)
            onDisconnectCallback?.invoke()
        }
    }

    fun isConnected(): Boolean {
        return isConnected && socket != null && socket?.isConnected == true
    }
}