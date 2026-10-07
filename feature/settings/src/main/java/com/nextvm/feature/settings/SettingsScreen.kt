package com.nextvm.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result.data)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is SettingsEffect.LaunchSignIn -> signInLauncher.launch(effect.intent)
                is SettingsEffect.LaunchOverlayPermission -> context.startActivity(effect.intent)
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onIntent(SettingsIntent.Refresh)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Engine Info
            SettingsSection(
                title = "Engine",
                icon = Icons.Default.Memory,
                iconTint = MaterialTheme.colorScheme.primary
            ) {
                InfoRow(
                    icon = Icons.Default.Memory,
                    title = "Engine Status",
                    value = uiState.engineStatus
                )
                SettingsDivider()
                InfoRow(
                    icon = Icons.Default.Storage,
                    title = "Process Slots",
                    value = "${uiState.usedSlots} / ${uiState.totalSlots} in use"
                )
                SettingsDivider()
                InfoRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = "Installed Apps",
                    value = "${uiState.installedAppCount}"
                )
            }

            // Google Accounts
            GoogleAccountsSection(
                accounts = uiState.googleAccounts,
                onAddAccountClick = { viewModel.onIntent(SettingsIntent.StartAddAccount) },
                onRemoveAccount = { email ->
                    viewModel.onIntent(SettingsIntent.RemoveGlobalAccount(email))
                }
            )

            // Sandbox Settings
            SettingsSection(
                title = "Sandbox",
                icon = Icons.Default.Security,
                iconTint = MaterialTheme.colorScheme.secondary
            ) {
                ToggleRow(
                    icon = Icons.Default.Security,
                    title = "File Isolation",
                    description = "Redirect guest app file I/O to sandbox",
                    checked = uiState.fileIsolationEnabled,
                    onToggle = { viewModel.onIntent(SettingsIntent.ToggleFileIsolation) }
                )
                SettingsDivider()
                ToggleRow(
                    icon = Icons.Default.Shield,
                    title = "Permission Firewall",
                    description = "Control guest app permissions individually",
                    checked = uiState.permissionFirewallEnabled,
                    onToggle = { viewModel.onIntent(SettingsIntent.TogglePermissionFirewall) }
                )
                SettingsDivider()
                ToggleRow(
                    icon = Icons.Default.Apps,
                    title = "Floating icon",
                    description = "Draggable icon on a virtual app. Tap it to change that app's speed.",
                    checked = uiState.floatingIconEnabled,
                    onToggle = { viewModel.onIntent(SettingsIntent.ToggleFloatingIcon) }
                )
            }

            // Debug
            SettingsSection(
                title = "Debug",
                icon = Icons.Default.BugReport,
                iconTint = MaterialTheme.colorScheme.tertiary
            ) {
                ToggleRow(
                    icon = Icons.Default.BugReport,
                    title = "Verbose Logging",
                    description = "Enable detailed engine logs via Timber",
                    checked = uiState.verboseLogging,
                    onToggle = { viewModel.onIntent(SettingsIntent.ToggleVerboseLogging) }
                )
            }

            // Danger Zone
            SettingsSection(
                title = "Danger Zone",
                icon = Icons.Default.Warning,
                iconTint = MaterialTheme.colorScheme.error
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Clear All Data",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Remove all virtual apps and their data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(SettingsIntent.ClearAllData) },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // About
            SettingsSection(
                title = "About",
                icon = Icons.Default.Info,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                InfoRow(
                    icon = Icons.Default.Info,
                    title = "Version",
                    value = "1.0.0-alpha01"
                )
                SettingsDivider()
                InfoRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = "Target SDK",
                    value = "35 (Android 15)"
                )
                SettingsDivider()
                InfoRow(
                    icon = Icons.Default.Memory,
                    title = "Architecture",
                    value = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = iconTint
            )
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            )
        )
    }
}

@Composable
private fun GoogleAccountsSection(
    accounts: List<GoogleAccountInfo>,
    onAddAccountClick: () -> Unit,
    onRemoveAccount: (String) -> Unit
) {
    var accountToRemove by remember { mutableStateOf<GoogleAccountInfo?>(null) }

    // Remove confirmation dialog
    accountToRemove?.let { account ->
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Remove Account") },
            text = {
                Text("Remove ${account.email} from NEXTVM? Virtual apps will no longer have access to this account.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveAccount(account.email)
                    accountToRemove = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    SettingsSection(
        title = if (accounts.isEmpty()) "Google Accounts" else "Google Accounts (${accounts.size})",
        icon = Icons.Default.Person,
        iconTint = MaterialTheme.colorScheme.primary
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            if (accounts.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "No Google accounts",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Add an account to use Google services in virtual apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                accounts.forEachIndexed { index, account ->
                    AccountRow(
                        account = account,
                        onRemove = { accountToRemove = account }
                    )
                    if (index < accounts.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }

        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onAddAccountClick() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Add Google Account",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AccountRow(account: GoogleAccountInfo, onRemove: () -> Unit) {
    // Generate a consistent avatar color from the email
    val avatarColors = listOf(
        Color(0xFF4285F4), // Google Blue
        Color(0xFF34A853), // Google Green
        Color(0xFFFBBC05), // Google Yellow
        Color(0xFFEA4335), // Google Red
        Color(0xFF673AB7), // Deep Purple
        Color(0xFF00BCD4), // Cyan
        Color(0xFFFF5722), // Deep Orange
        Color(0xFF009688), // Teal
    )
    val avatarColor = avatarColors[kotlin.math.abs(account.email.hashCode()) % avatarColors.size]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with unique color per email
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = avatarColor,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Display name as primary
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            // Email as secondary
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Added time
            if (account.addedTimeMillis > 0) {
                val timeAgo = remember(account.addedTimeMillis) {
                    val diff = System.currentTimeMillis() - account.addedTimeMillis
                    val minutes = diff / 60_000
                    val hours = minutes / 60
                    val days = hours / 24
                    when {
                        days > 0 -> "Added ${days}d ago"
                        hours > 0 -> "Added ${hours}h ago"
                        minutes > 0 -> "Added ${minutes}m ago"
                        else -> "Just added"
                    }
                }
                Text(
                    text = timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove account",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
