package be.benn0.pkmninstaller

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import be.benn0.pkmninstaller.apkm.ApkmBundle
import be.benn0.pkmninstaller.install.InstallProgress
import be.benn0.pkmninstaller.install.InstallState
import be.benn0.pkmninstaller.install.PreflightWarning
import be.benn0.pkmninstaller.shizuku.ShizukuState
import be.benn0.pkmninstaller.ui.PokeBall
import be.benn0.pkmninstaller.ui.theme.PkmnInstallerTheme
import be.benn0.pkmninstaller.util.formatBytes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PkmnInstallerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
private fun AppScreen(viewModel: MainViewModel = viewModel()) {
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val shellOutput by viewModel.shellOutput.collectAsStateWithLifecycle()
    val shellRunning by viewModel.shellRunning.collectAsStateWithLifecycle()
    val parseState by viewModel.parseState.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val allowDowngrade by viewModel.allowDowngrade.collectAsStateWithLifecycle()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            viewModel.inspectApkm(uri)
        }
    }

    // Android 13+ needs runtime permission to show the progress notification.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PokeBall(size = 44.dp)
            Column {
                Text(
                    text = "PKMN Installer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Gotta install 'em all!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        SetupCard(
            state = shizukuState,
            shellOutput = shellOutput,
            shellRunning = shellRunning,
            onRequestPermission = viewModel::requestShizukuPermission,
            onRunTest = viewModel::runShellTest,
        )

        ApkmCard(
            state = parseState,
            onPick = { picker.launch(arrayOf("*/*")) },
        )

        InstallCard(
            parseState = parseState,
            installState = installState,
            shizukuState = shizukuState,
            warnings = warnings,
            allowDowngrade = allowDowngrade,
            onAllowDowngradeChange = viewModel::setAllowDowngrade,
            onInstall = { pickedUri?.let(viewModel::installApkm) },
            onReset = viewModel::resetInstall,
        )

        DeviceInfoCard()
    }
}

@Composable
private fun SetupCard(
    state: ShizukuState,
    shellOutput: String?,
    shellRunning: Boolean,
    onRequestPermission: () -> Unit,
    onRunTest: () -> Unit,
) {
    SectionCard(title = "Setup") {
        when (state) {
            ShizukuState.NotRunning -> {
                Text(
                    text = "Before you can install anything, this app needs Shizuku " +
                        "running on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "1. Install the Shizuku app\n" +
                        "2. Start it via wireless debugging or ADB\n" +
                        "3. Come back to this screen",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ShizukuState.PermissionRequired -> {
                Text(
                    text = "Shizuku is running. Grant it permission so this app can " +
                        "install bundles for you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestPermission) {
                    Text("Grant permission")
                }
            }

            ShizukuState.PermissionDenied -> Text(
                text = "Permission was denied. Open the Shizuku app, allow this app, " +
                    "then come back to this screen.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is ShizukuState.Ready -> Text(
                text = "✓ Shizuku is ready — you're good to go!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (state is ShizukuState.Ready) {
            AdvancedSection {
                InfoRow("Identity", "uid ${state.uid} (${if (state.uid == 0) "root" else "shell"})")
                Button(onClick = onRunTest, enabled = !shellRunning) {
                    Text(if (shellRunning) "Running…" else "Run shell test (id)")
                }
                if (shellRunning) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
                shellOutput?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApkmCard(
    state: ParseState,
    onPick: () -> Unit,
) {
    SectionCard(title = "Choose a bundle") {
        Button(onClick = onPick) {
            Text("Select .apkm file")
        }
        Spacer(Modifier.height(8.dp))

        when (state) {
            ParseState.Idle -> Text(
                text = "Pick an .apkm file to get started.",
                style = MaterialTheme.typography.bodyMedium,
            )

            ParseState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("Reading the bundle…")
            }

            is ParseState.Error -> Text(
                text = "Couldn't read that file: ${state.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is ParseState.Success -> ApkmDetails(state.fileName, state.bundle)
        }
    }
}

@Composable
private fun ApkmDetails(fileName: String, bundle: ApkmBundle) {
    val info = bundle.info
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfoRow("App", info.packageName)
        InfoRow("Version", info.versionName ?: info.versionCode.toString())
        InfoRow("Size", formatBytes(bundle.totalSize))

        AdvancedSection {
            InfoRow("File", fileName)
            InfoRow("Package", info.packageName)
            InfoRow("Version code", info.versionCode.toString())
            InfoRow("Min API", info.minApi?.toString() ?: "?")
            InfoRow("Arches", info.arches.joinToString(", ").ifBlank { "?" })
            InfoRow("APKM version", info.apkmVersion?.toString() ?: "?")
            HorizontalDivider()
            Text(
                text = "APKs (${bundle.apks.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            bundle.apks.forEach { apk ->
                InfoRow(
                    label = apk.splitName + if (apk.isBase) "  (base)" else "",
                    value = formatBytes(apk.size),
                )
            }
        }
    }
}

@Composable
private fun InstallCard(
    parseState: ParseState,
    installState: InstallState,
    shizukuState: ShizukuState,
    warnings: List<PreflightWarning>,
    allowDowngrade: Boolean,
    onAllowDowngradeChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onReset: () -> Unit,
) {
    SectionCard(title = "Install") {
        val parsed = parseState as? ParseState.Success
        if (parsed == null) {
            Text(
                text = "Choose a bundle above to enable installation.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        val running = installState is InstallState.Running
        val shizukuReady = shizukuState is ShizukuState.Ready
        val blocked = warnings.any { it.blocking }

        warnings.forEach { warning ->
            Text(
                text = "⚠ ${warning.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (warning.blocking) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (warnings.isNotEmpty()) Spacer(Modifier.height(4.dp))

        Button(
            onClick = onInstall,
            enabled = !running && shizukuReady && !blocked,
        ) {
            Text(if (running) "Installing…" else "Install ${parsed.bundle.info.packageName}")
        }
        if (!shizukuReady) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Finish the setup above before installing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))

        when (installState) {
            InstallState.Idle -> Text(
                text = "This installs the bundle just like the Play Store would. " +
                    "It keeps running if you leave the app.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is InstallState.Running -> InstallProgressView(installState.progress)

            is InstallState.Success -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "✓ All done! ${installState.packageName} is installed.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                AdvancedSection {
                    InfoRow("Installer package", installState.installerPackage)
                    InfoRow(
                        "Verified on device",
                        installState.verified ?: "could not read back",
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Text(
                            text = "On Android 14+ the shell user can set the installer " +
                                "package but not the originating package, so only the " +
                                "installer field is spoofed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onReset) { Text("Done") }
            }

            is InstallState.Error -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Install failed",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = installState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onReset) { Text("Dismiss") }
            }
        }

        if (installState !is InstallState.Running) {
            AdvancedSection {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = allowDowngrade,
                        onCheckedChange = onAllowDowngradeChange,
                    )
                    Text(
                        text = "Allow installing an older version",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallProgressView(progress: InstallProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (progress) {
            is InstallProgress.Creating -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Getting ready…")
                }
            }

            is InstallProgress.Writing -> {
                Text(
                    text = "Installing… (${progress.apkIndex + 1}/${progress.apkCount})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress.totalBytesWritten.toFloat() / progress.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "${formatBytes(progress.totalBytesWritten)} / " +
                        formatBytes(progress.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is InstallProgress.Committing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Finishing up…")
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoCard() {
    val rows = listOf(
        "Android version" to Build.VERSION.RELEASE,
        "API level" to Build.VERSION.SDK_INT.toString(),
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Primary ABI" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        "Free storage" to formatBytes(freeStorageBytes()),
    )
    SectionCard(title = "About this device") {
        AdvancedSection {
            rows.forEach { (label, value) ->
                InfoRow(label, value)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PokeBall(size = 20.dp)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * Collapsible container for the technical details that are useful when
 * debugging but not for everyday use.
 */
@Composable
private fun AdvancedSection(content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide technical details" else "Show technical details")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun freeStorageBytes(): Long {
    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    return stat.availableBlocksLong * stat.blockSizeLong
}
