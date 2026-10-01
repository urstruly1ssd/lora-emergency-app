package com.example.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import com.example.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

// --- STATIC UTILS FOR COLOR RATIOS ---
@Composable
fun getRssiColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF2E7D32) // Strong (Green)
        rssi >= -70 -> Color(0xFFFBC02D) // Fair (Yellow)
        else -> Color(0xFFC62828)        // Critical (Red)
    }
}

// --- MASTER COMPOSABLE TO DISPATCH SCREENS ---
@Composable
fun LoRaEmergencyApp(viewModel: MainActivityViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val showRecipientSelector by viewModel.showRecipientSelector.collectAsState()
    val showSOSDialog by viewModel.showSOSDialog.collectAsState()
    val connectedNode by viewModel.connectedNode.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val gpsLoc by viewModel.gpsLocation.collectAsState()
    val isBeaconActive by viewModel.isBeaconActive.collectAsState()
    val queueCount by viewModel.unsentMessagesCount.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (currentScreen != AppScreen.Splash) {
                EmergencyBottomNavigation(
                    currentScreen = currentScreen,
                    onNavigate = { viewModel.navigateTo(it) }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Render current screen path
            Crossfade(
                targetState = currentScreen,
                animationSpec = tween(300),
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    AppScreen.Splash -> SplashScreen()
                    AppScreen.Dashboard -> DashboardScreen(viewModel)
                    AppScreen.NodeScan -> NodeScanScreen(viewModel)
                    AppScreen.ChatsList -> ChatsListScreen(viewModel)
                    AppScreen.ChatThread -> ChatThreadScreen(viewModel)
                    AppScreen.Offline -> OfflineDiagnosticsScreen(viewModel)
                    AppScreen.Map -> OfflineMapScreen(viewModel)
                }
            }

            // Global Overlays: Recipient list, SOS beacons, Top Status alerts
            if (showRecipientSelector) {
                RecipientSelectorModal(
                    viewModel = viewModel,
                    onDismiss = { viewModel.toggleRecipientSelector(false) }
                )
            }

            if (showSOSDialog) {
                SOSActiveDialog(
                    gps = gpsLoc,
                    battery = batteryLevel,
                    onDeactivate = { viewModel.deactivateSOS() },
                    onDismiss = { viewModel.dismissSOSDialog() }
                )
            }

            // Floating Top Telemetry Bar (Visible on all screens except Splash)
            if (currentScreen != AppScreen.Splash) {
                GlobalTelemetryHeader(
                    connectedNode = connectedNode,
                    isBeaconActive = isBeaconActive,
                    queueCount = queueCount,
                    battery = batteryLevel,
                    gps = gpsLoc,
                    currentScreen = currentScreen,
                    onBack = {
                        when (currentScreen) {
                            AppScreen.ChatThread -> viewModel.navigateTo(AppScreen.ChatsList)
                            AppScreen.NodeScan -> viewModel.navigateTo(AppScreen.Dashboard)
                            AppScreen.Offline -> viewModel.navigateTo(AppScreen.Dashboard)
                            AppScreen.ChatsList -> viewModel.navigateTo(AppScreen.Dashboard)
                            AppScreen.Map -> viewModel.navigateTo(AppScreen.Dashboard)
                            else -> viewModel.navigateTo(AppScreen.Dashboard)
                        }
                    }
                )
            }
        }
    }
}

// --- IMMERSIVE CUSTOM BOTTOM NAVIGATION BAR ---
@Composable
fun EmergencyBottomNavigation(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color(0xFF121416), // Slate bottom navigation background
        border = BorderStroke(1.dp, Color(0x1AFFFFFF)) // White border/5 opacity
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                NavigationTabItem(
                    screen = AppScreen.Dashboard,
                    label = "ALERT",
                    icon = Icons.Default.Home,
                    activeColor = Color(0xFFFF5252)
                ),
                NavigationTabItem(
                    screen = AppScreen.NodeScan,
                    label = "NODES",
                    icon = Icons.Default.CellTower,
                    activeColor = Color(0xFFFF5252)
                ),
                NavigationTabItem(
                    screen = AppScreen.ChatsList,
                    label = "CHAT",
                    icon = Icons.Default.Chat,
                    activeColor = Color(0xFFFF5252)
                ),
                NavigationTabItem(
                    screen = AppScreen.Map,
                    label = "MAP",
                    icon = Icons.Default.Map,
                    activeColor = Color(0xFFFF5252)
                )
            )

            tabs.forEach { tab ->
                val isActive = when (tab.screen) {
                    AppScreen.Dashboard -> currentScreen == AppScreen.Dashboard
                    AppScreen.NodeScan -> currentScreen == AppScreen.NodeScan
                    AppScreen.ChatsList -> currentScreen == AppScreen.ChatsList || currentScreen == AppScreen.ChatThread
                    AppScreen.Map -> currentScreen == AppScreen.Map
                    else -> false
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigate(tab.screen) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("nav_tab_${tab.label.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 32.dp)
                            .background(
                                color = if (isActive) Color(0x33B71C1C) else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isActive) tab.activeColor else Color(0x66FFFFFF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        color = if (isActive) tab.activeColor else Color(0x66FFFFFF),
                        fontSize = 10.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

data class NavigationTabItem(
    val screen: AppScreen,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val activeColor: Color
)

// --- GLOBAL TELEMETRY ACCENT HEADER ---
@Composable
fun GlobalTelemetryHeader(
    connectedNode: NodeEntity?,
    isBeaconActive: Boolean,
    queueCount: Int,
    battery: Int,
    gps: Pair<Double, Double>,
    currentScreen: AppScreen,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, Color(0xFF222222))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Screen Title or Back Button
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentScreen != AppScreen.Dashboard) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .testTag("back_button")
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                val titleText = when (currentScreen) {
                    AppScreen.Dashboard -> "LoRa Emergency"
                    AppScreen.NodeScan -> "Radio Nodes"
                    AppScreen.ChatsList -> "Emergency Comms"
                    AppScreen.ChatThread -> "Secure Link"
                    AppScreen.Offline -> "RF Offline Mode"
                    AppScreen.Map -> "Rescue Map"
                    else -> "LoRa"
                }

                Text(
                    text = titleText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif
                )
            }

            // Real-time disaster Telemetry
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Signal Indicator Status
                if (connectedNode != null) {
                    val pulse = rememberInfiniteTransition(label = "").animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = ""
                    )
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Connected",
                        tint = getRssiColor(connectedNode.rssi).copy(alpha = pulse.value),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${connectedNode.rssi} dBm",
                        fontSize = 11.sp,
                        color = getRssiColor(connectedNode.rssi),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "Disconnected",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "NO LINK",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))

                // Offline Message Queue
                if (queueCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Q: $queueCount",
                            fontSize = 10.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                // Battery Info Area
                val batteryIcon = when {
                    battery > 80 -> Icons.Default.BatteryFull
                    battery > 50 -> Icons.Default.BatteryChargingFull
                    battery > 20 -> Icons.Default.Battery4Bar
                    else -> Icons.Default.BatteryAlert
                }
                Icon(
                    imageVector = batteryIcon,
                    contentDescription = "Battery Status",
                    tint = if (battery < 20) Color(0xFFFF1744) else Color(0xFFECEFF1),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "$battery%",
                    fontSize = 11.sp,
                    color = if (battery < 20) Color(0xFFFF1744) else Color(0xFFECEFF1),
                )
            }
        }
    }
}

// --- 1. SPLASH SCREEN (3S) WITH PULSING SIREN ANIMATION ---
@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp)
        ) {
            // Pulse Ripples behind Siren Center
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    color = Color(0xFFB71C1C),
                    radius = 90.dp.toPx() * scaleAnim,
                    center = centerOffset,
                    alpha = 0.3f * (1.2f - scaleAnim)
                )
                drawCircle(
                    color = Color(0xFFFF1744),
                    radius = 120.dp.toPx() * scaleAnim * 1.2f,
                    center = centerOffset,
                    alpha = 0.15f * (1.2f - scaleAnim)
                )
            }

            // High Fidelity Siren Icon Glowing Center
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Distress Signal Beacon",
                    tint = Color(0xFFFF1744),
                    modifier = Modifier.size(96.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "LORA EMERGENCY",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "OFF-GRID DISASTER COMMS INTEROP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFFF1744),
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(40.dp))
        
        CircularProgressIndicator(
            color = Color(0xFFB71C1C),
            strokeWidth = 3.dp,
            modifier = Modifier.size(24.dp)
        )
    }
}

// --- 2. DASHBOARD SCREEN WITH MASSIVE RED HOLD SOS BUTTON ---
@Composable
fun DashboardScreen(viewModel: MainActivityViewModel) {
    val connectedNode by viewModel.connectedNode.collectAsState()
    val isBeaconActive by viewModel.isBeaconActive.collectAsState()
    val queueCount by viewModel.unsentMessagesCount.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Hold SOS variables
    val holdInteractionSource = remember { MutableInteractionSource() }
    val isHoldPressed by holdInteractionSource.collectIsPressedAsState()
    
    // Hold animation logic (0.0 to 1.0 in 3 seconds)
    var progress by remember { mutableStateOf(0f) }
    
    LaunchedEffect(isHoldPressed) {
        if (isHoldPressed) {
            val vibrator = try {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Throwable) {
                null
            }
            var elapsed = 0L
            while (elapsed < 3000L && isHoldPressed) {
                delay(100)
                elapsed += 100
                progress = elapsed / 3000f
                // Custom tactile continuous pulse build-up
                if (elapsed % 300 == 0L) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(50)
                        }
                    } catch (e: Throwable) {
                        // Suppress all vibration crashes (such as missing permission or virtual hardware failure)
                    }
                }
            }
            if (isHoldPressed && progress >= 0.99f) {
                // Sound / Haptic Heavy Shock on Trigger
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(500)
                    }
                } catch (e: Throwable) {
                    // Suppress haptic errors
                }
                viewModel.triggerSOSHold()
                progress = 0f
            }
        } else {
            progress = 0f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(72.dp)) // Clearance for Global Telemetry Bar

        // Operational Status Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1C1E)
            ),
            border = BorderStroke(
                width = 1.dp,
                color = Color(0x1AFFFFFF)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (connectedNode != null) Color(0xFF1B5E20) else Color(0xFF9E2A2B),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (connectedNode != null) Icons.Default.LeakAdd else Icons.Default.SyncProblem,
                        contentDescription = "Status icon",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (connectedNode != null) "CONNECTED GATEWAY" else "OFF-GRID BEACON (STANDALONE)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (connectedNode != null) {
                            "${connectedNode?.name} • Distance: ~${((connectedNode?.distanceEstimate ?: 0.0) * 1000).toInt()}m"
                        } else {
                            "Broadcasting purely via peer mesh. Active queue: $queueCount"
                        },
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        // MASSIVE RED SOS CONTROL AREA
        Box(
            modifier = Modifier
                .padding(vertical = 24.dp)
                .fillMaxWidth(0.9f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            // Ripple Background Scale
            val scaleRipple = rememberInfiniteTransition(label = "").animateFloat(
                initialValue = 0.95f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = ""
            )
            val alphaRipple = rememberInfiniteTransition(label = "").animateFloat(
                initialValue = 0.35f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = ""
            )

            // Canvas drawing ripples behind button circle
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    color = Color(0xFFFF1744),
                    radius = (size.width / 2.1f) * scaleRipple.value,
                    center = centerOffset,
                    alpha = alphaRipple.value
                )
            }

            // Main Active Hold Circle with continuous gradient and glowing border
            Box(
                modifier = Modifier
                    .fillMaxSize(0.85f)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFD32F2F), Color(0xFFB71C1C))
                        )
                    )
                    .border(4.dp, Color(0xFFFF5252).copy(alpha = 0.2f), CircleShape)
                    .testTag("sos_button_container"),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {}, // Handled strictly via continuous hold interactions
                    interactionSource = holdInteractionSource,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        contentColor = Color.White,
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxSize().testTag("sos_button")
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CrisisAlert,
                            contentDescription = "Alert siren",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isHoldPressed) "HOLDING..." else "SOS",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isHoldPressed) "${(progress * 100).roundToInt()}%" else "PRESS & HOLD 3S",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Circular progress ring showing hold status
            if (progress > 0f) {
                CircularProgressIndicator(
                    progress = progress,
                    color = Color.White,
                    strokeWidth = 6.dp,
                    modifier = Modifier.fillMaxSize(0.9f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // GRID BUTTON PANEL FOR ALL SECTIONS
        Text(
            text = "TACTICAL INTERRUPT CHANNELS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardShortcutCard(
                title = "LoRa Nodes",
                subtitle = "Scan and Link Gateways",
                icon = Icons.Default.CellTower,
                color = Color(0xFFD32F2F),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.navigateTo(AppScreen.NodeScan) }
            )
            DashboardShortcutCard(
                title = "Secure chat",
                subtitle = "Text emergency dispatchers",
                icon = Icons.Default.Chat,
                color = Color(0xFFFF5252),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.navigateTo(AppScreen.ChatsList) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardShortcutCard(
                title = "Vector Map",
                subtitle = "View evacuation corridors",
                icon = Icons.Default.Map,
                color = Color(0xFF388E3C),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.navigateTo(AppScreen.Map) }
            )
            DashboardShortcutCard(
                title = "Diagnostics",
                subtitle = "Offline RF metrics",
                icon = Icons.Default.CompassCalibration,
                color = Color(0xFF1976D2),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.navigateTo(AppScreen.Offline) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Shake Advice Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = "Device shake",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SHAKE DIRECTIVES: Shake phone vigorously in an immediate landslide/entombment crisis to auto-broadcast GPS beacon coordinates.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun DashboardShortcutCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)),
        border = BorderStroke(1.dp, Color(0x1AFFFFFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// --- 3. BLE NODE DISCOVERY SCREEN ---
@Composable
fun NodeScanScreen(viewModel: MainActivityViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val connectedNode by viewModel.connectedNode.collectAsState()
    val scope = rememberCoroutineScope()

    // Trigger auto-scan on open
    LaunchedEffect(Unit) {
        viewModel.triggerScan()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(72.dp)) // clearance

        // Top Rescan Title Area
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Nearby Disaster Gateways",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Searching on LoRa RF band ESP32_LoRa_*",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            IconButton(
                onClick = { viewModel.triggerScan() },
                modifier = Modifier.background(Color(0xFF222222), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Rescan RF frequency",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Processing status indicator
        AnimatedVisibility(visible = isScanning) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1414)),
                border = BorderStroke(1.dp, Color(0xFFD32F2F))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFF1744),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Calibrating RF transceiver spectrum... Scanning.",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Grid View representation (Dual-column layout)
        if (allNodes.isEmpty() && !isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SignalWifiStatusbarNull,
                        contentDescription = "Null nodes",
                        tint = Color.Gray,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "NO LOCAL NODES RECEIVED YET",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Ensure your physical antenna is connected. Tap Scan.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(allNodes, key = { it.name }) { node ->
                    val isNodeConnected = connectedNode?.name == node.name
                    NodeCardView(
                        node = node,
                        isConnected = isNodeConnected,
                        onConnect = {
                            if (isNodeConnected) {
                                viewModel.disconnectNode()
                            } else {
                                viewModel.connectToNode(node.name)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Fast Actions panel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.autoConnectBest() },
                modifier = Modifier.weight(1.5f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
            ) {
                Icon(
                    imageVector = Icons.Default.AutoMode,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("AUTO-LINK BEST", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { viewModel.navigateTo(AppScreen.Offline) },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Color.Gray)
            ) {
                Text("OFFLINE FORCE", fontSize = 12.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun NodeCardView(
    node: NodeEntity,
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFF0C1F0E) else Color(0xFF141414)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isConnected) Color(0xFF2E7D32) else Color(0xFF2B2B2B)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CellTower else Icons.Outlined.CellTower,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF2E7D32) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = node.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // RSSI Indicator bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RSSI: ${node.rssi} dBm",
                        fontSize = 11.sp,
                        color = getRssiColor(node.rssi),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Est: ${((node.distanceEstimate) * 1000).toInt()}m",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Visual colored rssi strength index lines
                Row(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val activeBars = when {
                        node.rssi >= -50 -> 4
                        node.rssi >= -70 -> 3
                        node.rssi >= -85 -> 2
                        else -> 1
                    }
                    val signalColor = getRssiColor(node.rssi)
                    for (i in 1..4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (i <= activeBars) signalColor else Color(0xFF2B2B2B))
                        )
                    }
                }
            }

            // Connection selection controller action triggers
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFF2E7D32) else Color(0xFF2E2E2E)
                )
            ) {
                Text(
                    text = if (isConnected) "LINKED" else "CONNECT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// --- 4. STREAMLINED ESP32 SERIAL MONITOR CONTROLLER ---
@Composable
fun ChatsListScreen(viewModel: MainActivityViewModel) {
    val connectedNode by viewModel.connectedNode.collectAsState()

    if (connectedNode != null) {
        ChatThreadScreen(viewModel)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0x11FF1744), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = "No Connected Node",
                    tint = Color(0xFFFF1744),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "SERIAL LINK OFFLINE",
                fontSize = 20.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No ESP32 transceiver is currently linked to the device. Please go to the NODES scanning page to discover and connect your board.",
                color = Color.Gray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { viewModel.navigateTo(AppScreen.NodeScan) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("DISCOVER & LINK ESP32", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ResponderHeaderSection(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF1744),
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun ResponderChatItem(
    responder: ResponderEntity,
    onChatRequest: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Purge Frequency Logs?", color = Color.White) },
            text = { Text("This will permanently wipe all local Room database message history with ${responder.name}.", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(responder.id)
                    showDialog = false
                }) {
                    Text("PURGE", color = Color(0xFFFF1744))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("CANCEL", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    Row(
        primaryClickable = Modifier
            .fillMaxWidth()
            .clickable { onChatRequest(responder.id) }
            .padding(vertical = 12.dp),
        responder = responder,
        onLongClick = { showDialog = true }
    )
}

@Composable
fun Row(
    primaryClickable: Modifier,
    responder: ResponderEntity,
    onLongClick: () -> Unit
) {
    Row(
        modifier = primaryClickable,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // High Contrast Badge Avatar representing responder initial
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(
                    color = if (responder.type == "Emergency") Color(0xFF7F1D1D) else Color(0xFF263238),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = responder.avatarInitial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Identification descriptors
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = responder.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Distance rating tag
                Text(
                    text = "${responder.distanceKm} km",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = responder.status,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Active status beacon blink circles
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (responder.type == "Emergency") Color(0xFFD32F2F) else Color(0xFF1B5E20),
                            shape = CircleShape
                        )
                )
            }
        }
        
        // Settings wipe action menu icon
        IconButton(onClick = onLongClick) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Delete communication node logs",
                tint = Color(0xFF2C2C2C),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// --- 5. CHAT THREAD SCREEN (REAL-TIME ESP32 SERIAL TERMINAL MONITOR) ---
@Composable
fun ChatThreadScreen(viewModel: MainActivityViewModel) {
    val activeResponderId by viewModel.activeResponderId.collectAsState()
    val messages by viewModel.messagesForActiveChat.collectAsState(initial = emptyList())
    val connectedNode by viewModel.connectedNode.collectAsState()
    val trackingGps by viewModel.gpsLocation.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val nodeName = activeResponderId ?: connectedNode?.name ?: "esp32_serial"

    // Auto scroll down to latest incoming index
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B0D)) // Deep CRT terminal background
    ) {
        Spacer(modifier = Modifier.height(72.dp)) // telemetry space

        // ESP32 Direct Serial Monitor Header Strip
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101418)),
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (connectedNode != null) Color(0xFF1B5E20) else Color(0xFF37474F),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Serial Port Terminal",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ESP32 Terminal Monitor",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Port: $nodeName • 115200 bps • Auto-Scroll",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Clear / Trash Button to empty terminal log history
                IconButton(
                    onClick = { viewModel.deleteChat(nodeName) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x11FFFFFF))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Serial Log Buffer",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Terminal Log monitor space
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(Color(0xFF05070A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp))
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "--- TERMINAL BUFFER EMPTY ---",
                        color = Color.DarkGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Log streams from RX / TX commands will display here in real-time.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isUser = message.isFromUser
                        val isSystem = message.text.startsWith("[SYSTEM")
                        
                        val timeStr = try {
                            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                                .format(java.util.Date(message.timestamp))
                        } catch (e: Exception) {
                            "00:00:00"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Time stamp in slate gray
                                Text(
                                    text = "[$timeStr] ",
                                    color = Color(0xFF607D8B),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )

                                // TX / RX Indicator prefix
                                val (prefix, prefixColor) = when {
                                    isSystem -> Pair("SYS* ", Color(0xFF29B6F6))
                                    isUser -> Pair("TX>  ", Color(0xFF00E676))
                                    else -> Pair("RX<  ", Color(0xFFFFA726))
                                }

                                Text(
                                    text = prefix,
                                    color = prefixColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )

                                // Message Payload
                                Text(
                                    text = message.text,
                                    color = if (isSystem) Color(0xFF90A4AE) else Color(0xFFECEFF1),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Monospace Hardware Commands Macros Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val macroCommands = listOf("AT", "AT+PING", "AT+BAT?", "AT+GPS?", "AT+HELP")
            macroCommands.forEach { cmd ->
                SuggestionChip(
                    onClick = { viewModel.submitMessage(cmd) },
                    label = { 
                        Text(
                            text = cmd, 
                            color = Color(0xFF00FF66), 
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ) 
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = Color(0xFF101418)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF1B5E20))
                )
            }
        }

        // Terminal Prompt Input Line Composer
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            color = Color(0xFF0D1115),
            border = BorderStroke(1.dp, Color(0xFF1C2229))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shell Prefix Label
                Text(
                    text = " esp32> ",
                    color = Color(0xFF00FF66),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 4.dp)
                )

                // Composer Text Field with completely zero border padding look
                BasicTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFF00FF66)),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.submitMessage(textInput)
                                textInput = ""
                            }
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (textInput.isEmpty()) {
                            Text(
                                text = "Enter command line...",
                                color = Color.DarkGray,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Transmit button styled like a serial carriage return trigger
                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            viewModel.submitMessage(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF1B5E20), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Transmit line to ESP32 serial interface",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// --- 6. OFFLINE DIRECTIONS & DIAGNOSTICS SCREEN ---
@Composable
fun OfflineDiagnosticsScreen(viewModel: MainActivityViewModel) {
    val searchNearbyResult by viewModel.allNodes.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(72.dp)) // clear

        Icon(
            imageVector = Icons.Default.SignalCellularConnectedNoInternet0Bar,
            contentDescription = "Zero active radio link detected",
            tint = Color(0xFFD32F2F),
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "NO LOCAL COMPATIBLE NODES FOUND",
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Analyzing available RF frequencies and hardware metrics...",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Diagnostic Steps Checklist Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
            border = BorderStroke(1.dp, Color(0xFF262626))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "FIELD ANTENNA DIAGNOSTIC GUIDE:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF1744)
                )
                Spacer(modifier = Modifier.height(12.dp))

                DiagnosticCheckboxRow(task = "Check whether LoRa antenna is securely threaded to SMA port.")
                DiagnosticCheckboxRow(task = "Ensure you are in direct Line-of-Sight or climb above blockades.")
                DiagnosticCheckboxRow(task = "Verify battery power to outer ESP32 node is over 3.3V.")
                DiagnosticCheckboxRow(task = "Maintain active mesh sync with other emergency users near you.")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Manual Calibration action triggers
        Button(
            onClick = { viewModel.navigateTo(AppScreen.NodeScan) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CellTower, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("PROBE LOCAL CHANNELS VIA SCAN")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { viewModel.navigateTo(AppScreen.Dashboard) },
            border = BorderStroke(1.dp, Color.Gray),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("FORCE RETURN TO BOARD", color = Color.White)
        }
    }
}

@Composable
fun DiagnosticCheckboxRow(task: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFBC02D),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = task,
            fontSize = 11.sp,
            color = Color.LightGray,
            lineHeight = 15.sp
        )
    }
}

// --- 7. OFFLINE TACTICAL MAP SCREEN ---
@Composable
fun OfflineMapScreen(viewModel: MainActivityViewModel) {
    val allResponders by viewModel.allResponders.collectAsState()
    val trackingGps by viewModel.gpsLocation.collectAsState()
    val isBeaconActive by viewModel.isBeaconActive.collectAsState()

    // Screen Drag Coordinate offset vectors for Map Pan/Zoom
    var mapOffsetX by remember { mutableStateOf(0f) }
    var mapOffsetY by remember { mutableStateOf(0f) }
    var mapMultiplier by remember { mutableStateOf(1f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Spacer(modifier = Modifier.height(72.dp)) // clear telemetry bar

        // Map Control Bar Info strip
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
            border = BorderStroke(1.dp, Color(0xFF262626))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "OFF-GRID VECTOR SLATE MAP",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Showing ${allResponders.size} rescue responder coordinates (GPS Offline)",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }

                // GPS coord summary
                Text(
                    text = "${trackingGps.first.toFloat()}°N, ${trackingGps.second.toFloat()}°E",
                    fontSize = 11.sp,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // MAP DRAWING CANVAS WINDOW
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                .background(Color(0xFF0C0C0E))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        mapOffsetX += dragAmount.x
                        mapOffsetY += dragAmount.y
                    }
                }
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse_circle")
            val gpsPulseRadius by infiniteTransition.animateFloat(
                initialValue = 10f,
                targetValue = 36f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = ""
            )
            val gpsPulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = ""
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Center projection coordinates anchor
                val zeroX = canvasWidth / 2f + mapOffsetX
                val zeroY = canvasHeight / 2f + mapOffsetY

                // 1. Draw Grid Isolation Lines
                val gridSize = (64f * mapMultiplier).coerceAtLeast(10f)
                val lineCountX = try { ((canvasWidth / gridSize).toInt() + 2).coerceIn(0, 100) } catch (e: Exception) { 20 }
                for (i in -1..lineCountX) {
                    val currentX = (zeroX % gridSize) + (i * gridSize)
                    drawLine(
                        color = Color(0xFF1B1B1E),
                        start = Offset(currentX, 0f),
                        end = Offset(currentX, canvasHeight),
                        strokeWidth = 1f
                    )
                }

                val lineCountY = try { ((canvasHeight / gridSize).toInt() + 2).coerceIn(0, 100) } catch (e: Exception) { 20 }
                for (i in -1..lineCountY) {
                    val currentY = (zeroY % gridSize) + (i * gridSize)
                    drawLine(
                        color = Color(0xFF1B1B1E),
                        start = Offset(0f, currentY),
                        end = Offset(canvasWidth, currentY),
                        strokeWidth = 1f
                    )
                }

                // 2. Draw Green Rescue Path Evacuation Corridor
                val evacCorridorPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(zeroX, zeroY)
                    quadraticTo(
                        zeroX + 120f, zeroY - 200f,
                        zeroX + 280f, zeroY - 340f
                    )
                }
                drawPath(
                    path = evacCorridorPath,
                    color = Color(0xFF1B5E20),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )

                // 3. Draw Active Transmitted SOS Beacon Trail (Dashed Red Lines)
                if (isBeaconActive) {
                    val sosTrailPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(zeroX, zeroY)
                        lineTo(zeroX - 100f, zeroY + 80f)
                        lineTo(zeroX - 180f, zeroY + 220f)
                    }
                    drawPath(
                        path = sosTrailPath,
                        color = Color(0xFFFF1744),
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    )
                }

                // 4. Draw Responders (Red target warning points) grouped near core area
                val countResponders = allResponders.size
                val divisor = if (countResponders > 0) countResponders else 1
                allResponders.forEachIndexed { index, resp ->
                    // Derive mock dynamic coordinates distributed across angles from the User central position
                    val angle = (index * (360f / divisor)).toDouble()
                    val radius = (resp.distanceKm * 180f) * mapMultiplier
                    val radians = Math.toRadians(angle)
                    val targetX = zeroX + (radius * Math.cos(radians)).toFloat()
                    val targetY = zeroY + (radius * Math.sin(radians)).toFloat()

                    // Only draw if within display map window limit
                    if (targetX in 0f..canvasWidth && targetY in 0f..canvasHeight) {
                        // Drawing small warning target anchor
                        drawCircle(
                            color = if (resp.type == "Emergency") Color(0xFFD32F2F) else Color(0xFF1976D2),
                            radius = 6f,
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = if (resp.type == "Emergency") Color(0xFFD32F2F) else Color(0xFF1976D2),
                            radius = 16f,
                            center = Offset(targetX, targetY),
                            style = Stroke(width = 1f)
                        )
                    }
                }

                // 5. Draw User Center Dot pulsing distress coordinates
                drawCircle(
                    color = Color(0xFF1E88E5),
                    radius = 8f,
                    center = Offset(zeroX, zeroY)
                )
                drawCircle(
                    color = Color(0xFF1E88E5),
                    radius = gpsPulseRadius,
                    center = Offset(zeroX, zeroY),
                    alpha = gpsPulseAlpha,
                    style = Stroke(width = 2f)
                )
            }

            // Top zoom controllers layer Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = { if (mapMultiplier < 3f) mapMultiplier += 0.2f },
                    modifier = Modifier.background(Color(0xFF222222), CircleShape)
                ) {
                    Icon(Icons.Default.Add, "Zoom out scale maps", tint = Color.White)
                }
                Spacer(modifier = Modifier.height(6.dp))
                IconButton(
                    onClick = { if (mapMultiplier > 0.5f) mapMultiplier -= 0.2f },
                    modifier = Modifier.background(Color(0xFF222222), CircleShape)
                ) {
                    Icon(Icons.Default.Remove, "Zoom in scale maps", tint = Color.White)
                }
                Spacer(modifier = Modifier.height(6.dp))
                IconButton(
                    onClick = {
                        mapOffsetX = 0f
                        mapOffsetY = 0f
                        mapMultiplier = 1f
                    },
                    modifier = Modifier.background(Color(0xFF222222), CircleShape)
                ) {
                    Icon(Icons.Default.ShareLocation, "Recenter viewport tracker center", tint = Color.White)
                }
            }

            // High Contrast legend listing
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color(0xFF222222))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("MAP DIRECTIVE LEGEND:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF1E88E5), CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("USER GPS PIN", fontSize = 8.sp, color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFFD32F2F), CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("EMERGENCY TEAMS", fontSize = 8.sp, color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp, 2.dp).background(Color(0xFF1B5E20)))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("EVAC CORRIDOR (GREEN ROAD)", fontSize = 8.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- RECIPIENT SELECTOR MODAL WINDOW (NEW CHAT FAB DIALOG) ---
@Composable
fun RecipientSelectorModal(
    viewModel: MainActivityViewModel,
    onDismiss: () -> Unit
) {
    val responders by viewModel.allResponders.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Initialize Secure Link",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxHeight(0.7f)) {
                Text(
                    "Connect with adjacent disaster services frequencies:",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(responders, key = { "modal_${it.id}" }) { responder ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openChatWith(responder.id) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            color = if (responder.type == "Emergency") Color(0xFFD32F2F) else Color(0xFF1A73E8),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        responder.avatarInitial,
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(responder.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Mesh dist: ~${responder.distanceKm}km • ${responder.type} band", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = Color.White)
            }
        },
        containerColor = Color(0xFF141414),
        modifier = Modifier.fillMaxWidth(0.9f)
    )
}

// --- ACTIVE SOS BROADCAST OVERLAY DIALOG ---
@Composable
fun SOSActiveDialog(
    gps: Pair<Double, Double>,
    battery: Int,
    onDeactivate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AirportShuttle,
                    contentDescription = null,
                    tint = Color(0xFFFF1744),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SOS BROADCAST ACTIVE",
                    color = Color(0xFFFF1744),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Your transceiver is transmitting repeating 30s distress beacon ping bursts immediately to Emergency services (NDRF, Medical, Police)...",
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "DIAGNOSTIC BEACON METRICS:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("• GPS coordinates: lat ${gps.first.toFloat()}° / lng ${gps.second.toFloat()}°", fontSize = 11.sp, color = Color.White)
                        Text("• Active payload power: LoRa mesh level max", fontSize = 11.sp, color = Color.White)
                        Text("• Hardware Battery reserve: ${battery}%", fontSize = 11.sp, color = Color.White)
                        Text("• Broadcast interval: Repeating every 30 sec", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDeactivate,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
            ) {
                Text("HALT BEACON", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("LET RUN BACKGROUND", color = Color.White)
            }
        },
        containerColor = Color(0xFF1E1414)
    )
}
