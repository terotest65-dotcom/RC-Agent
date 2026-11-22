package com.acme.rc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.acme.rc.accessibility.RcAccessibilityService
import com.acme.rc.agent.AgentConfig
import com.acme.rc.agent.AgentConnection
import com.acme.rc.agent.AgentService
import com.acme.rc.device.DeviceStateReader
import com.acme.rc.inventory.AppInventory
import com.acme.rc.model.AppInfo
import com.acme.rc.model.DeviceState
import com.acme.rc.model.Element
import com.acme.rc.model.ProbeResult
import com.acme.rc.model.Snapshot
import com.acme.rc.model.SnapshotStore
import com.acme.rc.ui.SnapshotView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RcTheme {
                val scope = rememberCoroutineScope()
                var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
                var device by remember { mutableStateOf(DeviceStateReader.read(this@MainActivity)) }

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { }

                LaunchedEffect(Unit) {
                    val needed = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed += Manifest.permission.POST_NOTIFICATIONS
                    }
                    // Optional: SMS OTP capture. Notification-based OTPs still work
                    // if this is denied.
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECEIVE_SMS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed += Manifest.permission.RECEIVE_SMS
                    }
                    if (needed.isNotEmpty()) {
                        permissions.launch(needed.toTypedArray())
                    }
                    apps = AppInventory.list(this@MainActivity)
                }

                fun refreshDevice() {
                    device = DeviceStateReader.read(this@MainActivity)
                }

                fun connectRelay(url: String) {
                    AgentConfig.setServerUrl(this@MainActivity, url)
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, AgentService::class.java)
                    )
                }

                fun stopRelay() {
                    this@MainActivity.stopService(
                        Intent(this@MainActivity, AgentService::class.java)
                    )
                    AgentConnection.connected = false
                    AgentConnection.status = "Stopped"
                }

                fun requireService(): RcAccessibilityService? {
                    val service = RcAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Enable the RC Agent accessibility service first",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return service
                }

                fun bringToFront() {
                    val intent = Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    this@MainActivity.applicationContext.startActivity(intent)
                }

                fun capture(packageName: String?) {
                    val service = requireService() ?: return
                    scope.launch {
                        SnapshotStore.busy = true
                        SnapshotStore.status = "Capturing…"
                        try {
                            if (packageName != null) {
                                if (!service.launchApp(packageName)) {
                                    SnapshotStore.status = "Could not launch $packageName"
                                    return@launch
                                }
                                delay(1500)
                                service.awaitPackage(packageName, 5000)
                                delay(600)
                            }
                            val snapshot = service.captureSnapshot()
                            SnapshotStore.lastSnapshot = snapshot
                            SnapshotStore.status = snapshot.error?.let { "Capture error: $it" }
                                ?: "Captured ${snapshot.elements.size} hotspots"
                            refreshDevice()
                            if (packageName != null) {
                                delay(300)
                                bringToFront()
                            }
                        } catch (t: Throwable) {
                            SnapshotStore.status = t.message ?: "Capture failed"
                        } finally {
                            SnapshotStore.busy = false
                        }
                    }
                }

                fun probe(packageName: String) {
                    val service = requireService() ?: return
                    scope.launch {
                        SnapshotStore.busy = true
                        SnapshotStore.status = "Probing ${packageName.substringAfterLast('.')}…"
                        try {
                            val result = service.probe(packageName)
                            SnapshotStore.probes[packageName] = result
                            SnapshotStore.status =
                                "${result.verdict}: ${packageName.substringAfterLast('.')}"
                        } catch (t: Throwable) {
                            SnapshotStore.status = t.message ?: "Probe failed"
                        } finally {
                            SnapshotStore.busy = false
                            refreshDevice()
                        }
                    }
                }

                fun probeTargets() {
                    val service = requireService() ?: return
                    scope.launch {
                        SnapshotStore.busy = true
                        val targets = apps.filter { it.isTarget }
                        if (targets.isEmpty()) {
                            SnapshotStore.status = "None of the v1 apps are installed"
                            SnapshotStore.busy = false
                            return@launch
                        }
                        try {
                            for ((index, app) in targets.withIndex()) {
                                SnapshotStore.status =
                                    "Probing ${app.label} (${index + 1}/${targets.size})…"
                                try {
                                    val result = service.probe(app.packageName)
                                    SnapshotStore.probes[app.packageName] = result
                                } catch (t: Throwable) {
                                    SnapshotStore.status =
                                        t.message ?: "Probe failed for ${app.label}"
                                }
                            }
                            SnapshotStore.status = "Probe complete"
                        } finally {
                            SnapshotStore.busy = false
                            refreshDevice()
                            delay(300)
                            bringToFront()
                        }
                    }
                }

                fun replayTap(snapshot: Snapshot, element: Element) {
                    val service = requireService() ?: return
                    scope.launch {
                        SnapshotStore.busy = true
                        SnapshotStore.status = "Replaying tap #${element.index}…"
                        try {
                            val ok = service.replayTap(snapshot, element)
                            SnapshotStore.status =
                                if (ok) "Tap #${element.index} replayed" else "Tap failed"
                            if (ok) {
                                SnapshotStore.lastSnapshot = service.captureSnapshot()
                            }
                        } catch (t: Throwable) {
                            SnapshotStore.status = t.message ?: "Tap failed"
                        } finally {
                            SnapshotStore.busy = false
                            refreshDevice()
                            delay(300)
                            bringToFront()
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("RC Agent · Phase 0", fontWeight = FontWeight.SemiBold)
                            },
                            actions = {
                                TextButton(onClick = {
                                    scope.launch {
                                        apps = AppInventory.list(this@MainActivity)
                                        refreshDevice()
                                    }
                                }) { Text("Refresh") }
                            }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            RelayCard(
                                connected = AgentConnection.connected,
                                status = AgentConnection.status,
                                deviceId = AgentConnection.deviceId,
                                initialUrl = AgentConfig.serverUrl(this@MainActivity),
                                onConnect = { url -> connectRelay(url) },
                                onStop = { stopRelay() }
                            )
                        }

                        item {
                            DeviceCard(
                                state = device,
                                onRefresh = { refreshDevice() },
                                onAccessibility = {
                                    DeviceStateReader.openAccessibilitySettings(this@MainActivity)
                                },
                                onNotifications = {
                                    DeviceStateReader.openNotificationListenerSettings(this@MainActivity)
                                },
                                onBattery = {
                                    DeviceStateReader.openBatteryOptimizationSettings(this@MainActivity)
                                }
                            )
                        }

                        item {
                            SnapshotCard(
                                snapshot = SnapshotStore.lastSnapshot,
                                busy = SnapshotStore.busy,
                                status = SnapshotStore.status,
                                onCaptureCurrent = { capture(null) },
                                onReplayTap = { snapshot, element ->
                                    replayTap(snapshot, element)
                                }
                            )
                        }

                        item { ProbeSummary(SnapshotStore.probes) }

                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Apps (${apps.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { probeTargets() },
                                    enabled = !SnapshotStore.busy &&
                                        RcAccessibilityService.instance != null
                                ) {
                                    Text("Probe v1 apps")
                                }
                            }
                        }

                        items(apps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                probe = SnapshotStore.probes[app.packageName],
                                busy = SnapshotStore.busy,
                                onOpen = {
                                    AppInventory.launch(this@MainActivity, app.packageName)
                                },
                                onCapture = { capture(app.packageName) },
                                onProbe = { probe(app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayCard(
    connected: Boolean,
    status: String,
    deviceId: String,
    initialUrl: String,
    onConnect: (String) -> Unit,
    onStop: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Relay",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(if (connected) "Connected" else "Offline", connected)
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebSocket URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (deviceId.isNotBlank()) {
                Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onConnect(url) }) {
                    Text(if (connected) "Reconnect" else "Save & connect")
                }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            Text(
                "No token needed. Emulator: ws://10.0.2.2:8787/ws. Phone: ws://<host-ip>:8787/ws.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(
    state: DeviceState,
    onRefresh: () -> Unit,
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.model} · Android ${state.androidSdk}",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip("Accessibility", state.accessibilityEnabled)
                StatusChip("Notifications", state.notificationAccess)
                StatusChip("Screen on", state.screenOn)
                StatusChip("Unlocked", !state.locked)
                StatusChip("Device Owner", state.deviceOwner)
                StatusChip("Stay awake", state.stayAwake)
                StatusChip("Battery ${state.batteryPercent}%", state.charging || state.batteryPercent > 20)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onAccessibility) { Text("Accessibility") }
                OutlinedButton(onClick = onNotifications) { Text("Notifications") }
                OutlinedButton(onClick = onBattery) { Text("Battery") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            if (!state.accessibilityEnabled) {
                Text(
                    "Enable RC Agent under Settings → Accessibility. On Android 13+, " +
                        "if the app is sideloaded, first use App info → ⋮ → Allow restricted settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!state.deviceOwner) {
                Text(
                    "Device Owner optional: set via QR or `adb shell dpm set-device-owner " +
                        "com.acme.rc/.admin.RcDeviceAdminReceiver`. Without it, keep the device " +
                        "unlocked and charging.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SnapshotCard(
    snapshot: Snapshot?,
    busy: Boolean,
    status: String?,
    onCaptureCurrent: () -> Unit,
    onReplayTap: (Snapshot, Element) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Last snapshot",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                TextButton(onClick = onCaptureCurrent, enabled = !busy) {
                    Text("Capture current")
                }
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (snapshot == null) {
                Text(
                    "No snapshot yet. Use Capture on an app below, or Capture current to test the pipeline.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (snapshot.error != null) {
                Text(
                    "Capture error: ${snapshot.error}",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val image = remember(snapshot.id) { snapshot.bitmap?.asImageBitmap() }
                if (image != null) {
                    SnapshotView(image, snapshot.elements)
                }
                Text(
                    "${snapshot.packageName ?: "?"} · ${snapshot.width}×${snapshot.height} · " +
                        "${snapshot.elements.size} hotspots · ${snapshot.nodeCount} nodes · " +
                        "${snapshot.imageBytes / 1024} KB · ${snapshot.elapsedMs} ms",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Use a Tap button to replay a coordinate tap (best effort). " +
                        "The real controller taps while the target app is foreground.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                snapshot.elements.take(8).forEach { element ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "#${element.index}",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(36.dp)
                        )
                        Text(
                            "${element.label} · ${element.className.substringAfterLast('.')}" +
                                (element.viewId?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onReplayTap(snapshot, element) },
                            enabled = !busy
                        ) { Text("Tap") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeSummary(probes: Map<String, ProbeResult>) {
    if (probes.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Probe results", style = MaterialTheme.typography.titleMedium)
            for ((packageName, result) in probes) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VerdictBadge(result.verdict)
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${result.clickableCount} clickable · ${result.viewIdCount} ids · " +
                            "${result.elapsedMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    probe: ProbeResult?,
    busy: Boolean,
    onOpen: () -> Unit,
    onCapture: () -> Unit,
    onProbe: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(app.label, fontWeight = FontWeight.SemiBold)
                        if (app.isTarget) {
                            Text("v1", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "${app.packageName} · ${app.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                probe?.let { VerdictBadge(it.verdict) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, enabled = !busy) { Text("Open") }
                OutlinedButton(onClick = onCapture, enabled = !busy) { Text("Capture") }
                TextButton(onClick = onProbe, enabled = !busy) { Text("Probe") }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    val background = if (ok) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
    val foreground = if (ok) Color(0xFF166534) else Color(0xFF991B1B)
    Text(
        label,
        color = foreground,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun VerdictBadge(verdict: String) {
    val (background, foreground) = when (verdict) {
        "controllable" -> Color(0xFFDCFCE7) to Color(0xFF166534)
        "partial" -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        else -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
    }
    Text(
        verdict,
        color = foreground,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
