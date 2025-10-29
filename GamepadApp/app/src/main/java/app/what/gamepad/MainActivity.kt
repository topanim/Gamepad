package app.what.gamepad


import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.what.gamepad.ui.theme.GamepadTheme
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var gamepadClient: GamePadClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gamepadClient = GamePadClient(this)

        setContent {
            GamepadTheme {
                // Фиксируем горизонтальную ориентацию
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1E1E2E)
                ) {
                    HorizontalGamePadLayout(gamepadClient)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gamepadClient.disconnect()
    }
}


@Composable
fun HorizontalGamePadLayout(gamepadClient: GamePadClient) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val scope = rememberCoroutineScope()
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var gamepadId by remember { mutableStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E2E))
    ) {
        // Фоновый градиент
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF2D1B69),
                        Color(0xFF1E1E2E),
                        Color(0xFF1E1E2E)
                    )
                )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Левая часть - Левый джойстик и кнопки
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Левый аналоговый стик
                AnalogStick(
                    modifier = Modifier.size(120.dp),
                    onPositionChanged = { x, y ->
                        gamepadClient.sendLeftStick(x, y)
                    },
                    stickColor = Color(0xFF4A148C)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Кнопки D-Pad
                DPadSection(
                    modifier = Modifier.size(140.dp),
                    onDirectionPressed = { direction ->
                        gamepadClient.sendDPadInput(direction)
                    },
                    onDirectionReleased = {
                        gamepadClient.sendDPadRelease()
                    }
                )
            }

            // Центральная часть - Статус и системные кнопки
            Column(
                modifier = Modifier.weight(0.8f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ConnectionStatusPanel(
                    status = connectionStatus,
                    gamepadId = gamepadId,
                    onConnect = {
                        scope.launch {
                            gamepadClient.setOnDisconnectCallback {
                                // Обновляем UI при неожиданном отключении
                                connectionStatus = "Disconnected"
                                gamepadId = -1
                            }
                            gamepadClient.connect("192.168.3.52", 8888) { string, id ->
                                Log.d("Gamepad", "$string $id")
                                connectionStatus = string
                                gamepadId = id ?: 0
                            }
                        }
                    },
                    onDisconnect = {
                        connectionStatus = "Disconnected"
                        gamepadId = -1
                        gamepadClient.disconnect()
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Системные кнопки
                SystemButtonsSection(
                    onMenuPressed = { gamepadClient.sendButtonPress("menu") },
                    onMenuReleased = { gamepadClient.sendButtonRelease("menu") },
                    onViewPressed = { gamepadClient.sendButtonPress("view") },
                    onViewReleased = { gamepadClient.sendButtonRelease("view") }
                )
            }

            // Правая часть - Правый джойстик и кнопки действия
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Кнопки действия (ABXY)
                ActionButtonsSection(
                    modifier = Modifier.size(160.dp),
                    onButtonPressed = { button ->
                        gamepadClient.sendButtonPress(button)
                    },
                    onButtonReleased = { button ->
                        gamepadClient.sendButtonRelease(button)
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Правый аналоговый стик
                AnalogStick(
                    modifier = Modifier.size(120.dp),
                    onPositionChanged = { x, y ->
                        gamepadClient.sendRightStick(x, y)
                    },
                    stickColor = Color(0xFFB71C1C)
                )
            }
        }

        // Триггеры и бамперы
        TopTriggerSection(
            onLeftTrigger = { value -> gamepadClient.sendLeftTrigger(value) },
            onRightTrigger = { value -> gamepadClient.sendRightTrigger(value) },
            onLeftBumperPress = { gamepadClient.sendButtonPress("lb") },
            onLeftBumperRelease = { gamepadClient.sendButtonRelease("lb") },
            onRightBumperPress = { gamepadClient.sendButtonPress("rb") },
            onRightBumperRelease = { gamepadClient.sendButtonRelease("rb") }
        )
    }
}

@Composable
fun AnalogStick(
    modifier: Modifier = Modifier,
    onPositionChanged: (Float, Float) -> Unit,
    stickColor: Color
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val maxDistance = 40f

    Box(
        modifier = modifier
            .background(Color(0x44FFFFFF), CircleShape)
            .border(2.dp, Color(0x88FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Внешний круг
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color(0x22FFFFFF), CircleShape)
                .border(1.dp, Color(0x44FFFFFF), CircleShape)
        )

        // Сам стик
        Box(
            modifier = Modifier
                .offset(
                    x = (position.x * maxDistance).dp,
                    y = (position.y * maxDistance).dp
                )
                .size(50.dp)
                .background(stickColor, CircleShape)
                .shadow(4.dp, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()

                            var newX = position.x + dragAmount.x / maxDistance
                            var newY = position.y + dragAmount.y / maxDistance

                            // Ограничиваем движение внутри круга
                            val distance = sqrt(newX * newX + newY * newY)
                            if (distance > 1f) {
                                newX /= distance
                                newY /= distance
                            }

                            position = Offset(newX, newY)
                            onPositionChanged(newX, newY)
                        },
                        onDragEnd = {
                            position = Offset.Zero
                            onPositionChanged(0f, 0f)
                        }
                    )
                }
        )
    }
}

@Composable
fun ActionButtonsSection(
    modifier: Modifier = Modifier,
    onButtonPressed: (String) -> Unit,
    onButtonReleased: (String) -> Unit
) {
    Box(modifier = modifier) {
        // Кнопка A (снизу)
        GamePadButton(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.BottomCenter)
                .offset(y = (-10).dp),
            color = Color(0xFF00C853),
            text = "A",
            onPress = { onButtonPressed("a") },
            onRelease = { onButtonReleased("a") }
        )

        // Кнопка B (справа)
        GamePadButton(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.CenterEnd)
                .offset(x = (-10).dp),
            color = Color(0xFFD50000),
            text = "B",
            onPress = { onButtonPressed("b") },
            onRelease = { onButtonReleased("b") }
        )

        // Кнопка X (слева)
        GamePadButton(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.CenterStart)
                .offset(x = 10.dp),
            color = Color(0xFF2196F3),
            text = "X",
            onPress = { onButtonPressed("x") },
            onRelease = { onButtonReleased("x") }
        )

        // Кнопка Y (сверху)
        GamePadButton(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.TopCenter)
                .offset(y = 10.dp),
            color = Color(0xFFFFD600),
            text = "Y",
            onPress = { onButtonPressed("y") },
            onRelease = { onButtonReleased("y") }
        )
    }
}

@Composable
fun GamePadButton(
    modifier: Modifier = Modifier,
    color: Color,
    text: String,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .background(if (isPressed) color.copy(alpha = 0.7f) else color, CircleShape)
            .shadow(8.dp, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress()
                        tryAwaitRelease()
                        isPressed = false
                        onRelease()
                    }
                )
            }
            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DPadSection(
    modifier: Modifier = Modifier,
    onDirectionPressed: (String) -> Unit,
    onDirectionReleased: () -> Unit
) {
    Box(modifier = modifier) {
        // Центральная часть
        Box(
            modifier = Modifier
                .size(50.dp)
                .align(Alignment.Center)
                .background(Color(0x44FFFFFF), CircleShape)
        )

        // Вверх
        DPadArrow(
            modifier = Modifier
                .size(40.dp, 60.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-15).dp),
            direction = "up",
            onPressed = onDirectionPressed,
            onReleased = onDirectionReleased
        )

        // Вниз
        DPadArrow(
            modifier = Modifier
                .size(40.dp, 60.dp)
                .align(Alignment.BottomCenter)
                .offset(y = 15.dp)
                .rotate(180f),
            direction = "down",
            onPressed = onDirectionPressed,
            onReleased = onDirectionReleased
        )

        // Влево
        DPadArrow(
            modifier = Modifier
                .size(60.dp, 40.dp)
                .align(Alignment.CenterStart)
                .offset(x = (-15).dp)
                .rotate(270f),
            direction = "left",
            onPressed = onDirectionPressed,
            onReleased = onDirectionReleased
        )

        // Вправо
        DPadArrow(
            modifier = Modifier
                .size(60.dp, 40.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 15.dp)
                .rotate(90f),
            direction = "right",
            onPressed = onDirectionPressed,
            onReleased = onDirectionReleased
        )
    }
}

@Composable
fun DPadArrow(
    modifier: Modifier = Modifier,
    direction: String,
    onPressed: (String) -> Unit,
    onReleased: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .background(
                if (isPressed) Color(0xFF888888) else Color(0xFF666666),
                RoundedCornerShape(20)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressed(direction)
                        tryAwaitRelease()
                        isPressed = false
                        onReleased()
                    }
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}


@Composable
fun BoxScope.TopTriggerSection(
    onLeftTrigger: (Float) -> Unit,
    onRightTrigger: (Float) -> Unit,
    onLeftBumperPress: () -> Unit,
    onLeftBumperRelease: () -> Unit,
    onRightBumperPress: () -> Unit,
    onRightBumperRelease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .align(Alignment.TopCenter),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Левый бампер и триггер
        Column(
            modifier = Modifier.width(120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Левый бампер
            var leftBumperPressed by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(25.dp)
                    .background(
                        if (leftBumperPressed) Color(0xFF555555) else Color(0xFF333333),
                        RoundedCornerShape(8.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                leftBumperPressed = true
                                onLeftBumperPress()
                                tryAwaitRelease()
                                leftBumperPressed = false
                                onLeftBumperRelease()
                            }
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("LB", color = Color.White, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Левый триггер
            var leftTriggerValue by remember { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(30.dp)
                    .background(Color(0xFF444444), RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                leftTriggerValue =
                                    (leftTriggerValue - dragAmount.y / 100f).coerceIn(0f, 1f)
                                onLeftTrigger(leftTriggerValue)
                            }
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "LT: ${(leftTriggerValue * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }

        // Правый бампер и триггер
        Column(
            modifier = Modifier.width(120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Правый бампер
            var rightBumperPressed by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(25.dp)
                    .background(
                        if (rightBumperPressed) Color(0xFF555555) else Color(0xFF333333),
                        RoundedCornerShape(8.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                rightBumperPressed = true
                                onRightBumperPress()
                                tryAwaitRelease()
                                rightBumperPressed = false
                                onRightBumperRelease()
                            }
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("RB", color = Color.White, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Правый триггер
            var rightTriggerValue by remember { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(30.dp)
                    .background(Color(0xFF444444), RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                rightTriggerValue =
                                    (rightTriggerValue - dragAmount.y / 100f).coerceIn(0f, 1f)
                                onRightTrigger(rightTriggerValue)
                            }
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "RT: ${(rightTriggerValue * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusPanel(
    status: String,
    gamepadId: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Индикатор статуса
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    color = when {
                        status.contains("Connected") -> Color(0xFF00C853)
                        status.contains("Error") -> Color(0xFFD50000)
                        else -> Color(0xFF666666)
                    },
                    CircleShape
                )
                .border(2.dp, Color.White, CircleShape)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = status,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        if (gamepadId != -1) {
            Text(
                text = "GamePad #$gamepadId",
                color = Color(0xFFFFD600),
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (status == "Disconnected") onConnect() else onDisconnect()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status == "Disconnected") Color(0xFF4CAF50) else Color(
                    0xFFF44336
                )
            )
        ) {
            Text(if (status == "Disconnected") "CONNECT" else "DISCONNECT")
        }
    }
}

@Composable
fun SystemButtonsSection(
    onMenuPressed: () -> Unit,
    onMenuReleased: () -> Unit,
    onViewPressed: () -> Unit,
    onViewReleased: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Кнопка View/Select
        var viewPressed by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(
                    if (viewPressed) Color(0xFF1976D2) else Color(0xFF2196F3),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            viewPressed = true
                            onViewPressed()
                            tryAwaitRelease()
                            viewPressed = false
                            onViewReleased()
                        }
                    )
                }
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("View", color = Color.White, fontSize = 10.sp)
        }

        // Кнопка Menu/Start
        var menuPressed by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(
                    if (menuPressed) Color(0xFF388E3C) else Color(0xFF4CAF50),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            menuPressed = true
                            onMenuPressed()
                            tryAwaitRelease()
                            menuPressed = false
                            onMenuReleased()
                        }
                    )
                }
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("Menu", color = Color.White, fontSize = 10.sp)
        }
    }
}