package com.nfcwriter.app.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nfcwriter.app.data.local.*
import com.nfcwriter.app.data.remote.ManagedAccessKey
import com.nfcwriter.app.data.remote.WriterManagedUrl
import com.nfcwriter.app.data.remote.NextMessageCard
import com.nfcwriter.app.data.remote.filterWriterManagedUrls
import com.nfcwriter.app.data.remote.resolveWriterUrl
import com.nfcwriter.app.data.remote.writerUrlReadyToWrite
import com.nfcwriter.app.data.remote.writerUrlUnavailableReason
import com.nfcwriter.app.R
import com.nfcwriter.app.BuildConfig
import com.nfcwriter.app.domain.NfcPayload
import com.nfcwriter.app.domain.normalizeWebUrl
import com.nfcwriter.app.domain.validateUri
import com.nfcwriter.app.nfc.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

private enum class Screen { DASHBOARD, CREATE, QUICK, READ, REWRITE_PICK, REWRITE_CONFIRM, BATCH, TAGS, PROJECTS, HISTORY, SETTINGS, SECURITY, ADMIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcWriterApp(vm: AppViewModel, engine: NfcEngine, nfc: NfcUiState, themeMode: ThemeMode, setThemeMode: (ThemeMode) -> Unit, openNfcSettings: () -> Unit) {
    val mockConfiguration by engine.mockConfiguration.collectAsState()
    val mockModeEnabled = BuildConfig.DEBUG && mockConfiguration.enabled
    val navigationLocked = nfc.isCriticalOperation
    BackHandler(enabled = navigationLocked) { /* Critical NFC operations own in-app Back. */ }
    if (!vm.configured()) {
        SetupRequiredScreen(); return
    }
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { signedIn = vm.restoreAccessSession(); if (signedIn == true) vm.syncNow() }
    if (signedIn == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return
    }
    if (signedIn == false) {
        AccessKeyScreen(vm) { signedIn = true }; return
    }
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var selectedPayload by remember { mutableStateOf<NfcPayload?>(null) }
    var rewriteSource by remember { mutableStateOf<TagSnapshot?>(null) }
    var rewritePayload by remember { mutableStateOf<NfcPayload?>(null) }
    val tags by vm.tags.collectAsStateWithLifecycleCompat()
    val projects by vm.projects.collectAsStateWithLifecycleCompat()
    val history by vm.history.collectAsStateWithLifecycleCompat()
    val pending by vm.pending.collectAsStateWithLifecycleCompat()
    val topLevelScreens = remember { setOf(Screen.DASHBOARD, Screen.TAGS, Screen.CREATE, Screen.HISTORY, Screen.SETTINGS) }
    val isTopLevel = screen in topLevelScreens
    val selectedTab = when (screen) {
        Screen.QUICK, Screen.READ, Screen.REWRITE_PICK, Screen.REWRITE_CONFIRM, Screen.BATCH, Screen.PROJECTS -> Screen.DASHBOARD
        Screen.SECURITY -> Screen.TAGS
        Screen.ADMIN -> Screen.SETTINGS
        else -> screen
    }
    val title = when (screen) {
        Screen.DASHBOARD -> "TapTag"
        Screen.TAGS -> "My tags"
        Screen.CREATE -> "Create"
        Screen.HISTORY -> "History"
        Screen.SETTINGS -> "Settings"
        Screen.QUICK -> "Quick write"
        Screen.READ -> "Read tag"
        Screen.REWRITE_PICK -> "Choose new content"
        Screen.REWRITE_CONFIRM -> "Confirm rewrite"
        Screen.BATCH -> "Batch write"
        Screen.PROJECTS -> "Projects"
        Screen.SECURITY -> "Tag security"
        Screen.ADMIN -> "Super admin"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!isTopLevel) {
                CenterAlignedTopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                    navigationIcon = {
                        IconButton({
                            when (screen) {
                                Screen.REWRITE_PICK -> screen = Screen.READ
                                Screen.REWRITE_CONFIRM -> screen = Screen.REWRITE_PICK
                                else -> { screen = Screen.DASHBOARD; engine.cancel() }
                            }
                        }, enabled = !navigationLocked) { Icon(Icons.Default.ArrowBackIosNew, "Back", Modifier.size(20.dp), tint = if (navigationLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (!navigationLocked) AppNavigationBar(selectedTab) { screen = it; engine.cancel() }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (mockModeEnabled) MockModeBanner()
                Box(Modifier.weight(1f)) { when (screen) {
                Screen.DASHBOARD -> Dashboard(engine.availability(), tags.size, history.count { it.createdAt >= startOfToday() }, projects.size, pending, openNfcSettings) { screen = it }
                Screen.CREATE -> PayloadEditor(
                    managedPickerEnabled = vm.isSuperAdmin(),
                    siteOrigin = vm.writerSiteOrigin(),
                    loadManagedUrls = { vm.listWriterManagedUrls() },
                    prepareNextMessageCard = { url, uid -> vm.prepareNextMessageCard(url, uid) },
                ) { payload, protection -> selectedPayload = payload; engine.begin(PendingNfcOperation.InspectThenWrite(payload, protection)) }
                Screen.QUICK -> QuickWriteScreen(
                    managedPickerEnabled = vm.isSuperAdmin(),
                    siteOrigin = vm.writerSiteOrigin(),
                    loadManagedUrls = { vm.listWriterManagedUrls() },
                    prepareNextMessageCard = { url, uid -> vm.prepareNextMessageCard(url, uid) },
                    onWrite = { payload -> selectedPayload = payload; engine.begin(PendingNfcOperation.InspectThenWrite(payload, ProtectionRequest.None)) },
                    openCreate = { screen = Screen.CREATE },
                )
                Screen.READ -> NfcOperationPanel(nfc, engine, { screen = Screen.SECURITY }, { source -> rewriteSource = source; screen = Screen.REWRITE_PICK }) { engine.begin(PendingNfcOperation.Read) }
                Screen.REWRITE_PICK -> PayloadEditor(
                    actionLabel = "CONTINUE",
                    allowProtection = false,
                    managedPickerEnabled = vm.isSuperAdmin(),
                    siteOrigin = vm.writerSiteOrigin(),
                    loadManagedUrls = { vm.listWriterManagedUrls() },
                    prepareNextMessageCard = { url, uid -> vm.prepareNextMessageCard(url, uid) },
                ) { payload, _ -> rewritePayload = payload; screen = Screen.REWRITE_CONFIRM }
                Screen.REWRITE_CONFIRM -> RewriteConfirmationScreen(rewriteSource, rewritePayload, { screen = Screen.REWRITE_PICK }) {
                    val source = rewriteSource
                    val payload = rewritePayload
                    if (source != null && payload != null) {
                        selectedPayload = payload
                        engine.begin(PendingNfcOperation.Rewrite(payload, source.uid, source))
                    }
                }
                Screen.BATCH -> BatchScreen(engine, nfc, vm, tags)
                Screen.TAGS -> TagLibrary(tags) { }
                Screen.PROJECTS -> ProjectsScreen(projects, vm)
                Screen.HISTORY -> HistoryScreen(history)
                Screen.SETTINGS -> SettingsScreen(vm, engine, engine.availability(), pending, themeMode, setThemeMode, { vm.syncNow() }, { vm.viewModelScopeCompat { vm.signOut(); signedIn = false } }, openNfcSettings) { screen = Screen.ADMIN }
                Screen.SECURITY -> SecurityScreen(engine, nfc, vm, tags)
                Screen.ADMIN -> AdminDashboard(vm)
                } }
            }
            val recoveryStage = nfc.stage in setOf(NfcStage.INTERRUPTED, NfcStage.CONNECTION_LOST, NfcStage.RECOVERY_REQUIRED, NfcStage.SAFE_FAILURE)
            if ((selectedPayload != null && nfc.stage != NfcStage.IDLE) || nfc.isCriticalOperation || recoveryStage) {
                NfcOverlay(nfc, engine, nfc.expectedPayload ?: selectedPayload, vm.writerSiteOrigin()) { selectedPayload = null; rewriteSource = null; rewritePayload = null; screen = Screen.DASHBOARD }
            }
        }
    }
}

@Composable private fun MockModeBanner() = Surface(Modifier.fillMaxWidth(), color = Color(0xFFFF9F0A)) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🧪", fontSize = 18.sp)
        Spacer(Modifier.width(9.dp))
        Column { Text("MOCK NFC MODE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = .8.sp); Text("No physical NFC operations are being performed.", color = Color.Black.copy(alpha = .72f), fontSize = 11.sp) }
    }
}

@Composable
private fun AppNavigationBar(selected: Screen, navigate: (Screen) -> Unit) {
    val destinations = listOf(
        Triple(Screen.DASHBOARD, Icons.Default.Home, "Home"),
        Triple(Screen.TAGS, Icons.Default.Nfc, "Tags"),
        Triple(Screen.CREATE, Icons.Default.Add, "Create"),
        Triple(Screen.HISTORY, Icons.Default.History, "History"),
        Triple(Screen.SETTINGS, Icons.Default.Settings, "Settings"),
    )
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            shape = RoundedCornerShape(23.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .34f)),
            shadowElevation = 14.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 7.dp, vertical = 6.dp)) {
                destinations.forEach { (screen, icon, label) ->
                    val active = selected == screen
                    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = if (pressed) .96f else 1f
                                scaleY = if (pressed) .96f else 1f
                                alpha = if (pressed) .68f else 1f
                            }
                            .clickable(interactionSource = interactionSource, indication = null) { navigate(screen) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(15.dp),
                            color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent,
                        ) {
                            Box(Modifier.width(39.dp).height(29.dp), contentAlignment = Alignment.Center) {
                                Icon(icon, label, Modifier.size(if (active) 22.dp else 21.dp), tint = color)
                            }
                        }
                        Spacer(Modifier.height(1.dp))
                        Text(label, color = color, fontSize = 9.sp, letterSpacing = .1.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable private fun AccessKeyScreen(vm: AppViewModel, onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accessKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp).widthIn(max = 440.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF050914)) {
                Image(painterResource(R.drawable.taptag_logo), "TapTag", Modifier.padding(10.dp).size(62.dp), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(20.dp))
            Text("TapTag", color = MaterialTheme.colorScheme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
            Spacer(Modifier.height(6.dp))
            Text("Enter your access key to manage NFC tags.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(30.dp))
            TextField(
                accessKey, { accessKey = it }, Modifier.fillMaxWidth(),
                label = { Text("Access key") },
                leadingIcon = { Icon(Icons.Default.Key, null) },
                trailingIcon = {
                    IconButton({ showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                colors = iosTextFieldColors(),
            )
            Spacer(Modifier.height(18.dp))
            message?.let {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
            Button(
                { scope.launch { busy = true; message = runCatching { vm.unlock(accessKey); vm.syncNow(); onSuccess(); null }.exceptionOrNull()?.message; busy = false } },
                Modifier.fillMaxWidth().height(54.dp),
                enabled = !busy && accessKey.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (busy) "Verifying…" else "Continue", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(20.dp))
            Text("Superadmin keys also work on the TapTag web dashboard.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable private fun iosTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable private fun SetupRequiredScreen() = Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Card { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error); Text("Supabase setup required", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Copy local.properties.example to local.properties and set SUPABASE_URL plus the public anon key. Administrative/service-role keys must never be used here.") } } }

@Composable private fun Dashboard(status: NfcAvailability, managed: Int, written: Int, projects: Int, pending: Int, openSettings: () -> Unit, navigate: (Screen) -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFF050914)) { Image(painterResource(R.drawable.taptag_logo), "TapTag", Modifier.padding(4.dp).size(48.dp), contentScale = ContentScale.Fit) }
            Spacer(Modifier.width(13.dp))
            Text("TapTag", fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-.8).sp)
        }
        Spacer(Modifier.height(5.dp))
        Text("Create, scan and organize tags in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
    item { StatusCard(status, openSettings) }
    item {
        Text("Quick actions", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                DashboardActionRow("Create a new tag", "Choose content and write to NFC", Icons.Default.Add, Color(0xFF0A84FF)) { navigate(Screen.CREATE) }
                DashboardDivider()
                DashboardActionRow("Quick write", "Write a link in seconds", Icons.Default.Bolt, Color(0xFFFF9F0A)) { navigate(Screen.QUICK) }
                DashboardDivider()
                DashboardActionRow("Read tag", "Inspect saved NFC content", Icons.Default.Nfc, Color(0xFF32ADE6)) { navigate(Screen.READ) }
                DashboardDivider()
                DashboardActionRow("Batch write", "Program multiple tags in sequence", Icons.Default.DynamicFeed, Color(0xFFAF52DE)) { navigate(Screen.BATCH) }
            }
        }
    }
    item {
        Text("Overview", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    OverviewCell("Managed tags", managed, Icons.Default.Nfc, Color(0xFF0A84FF), Modifier.weight(1f))
                    OverviewVerticalDivider()
                    OverviewCell("Written today", written, Icons.Default.CheckCircle, Color(0xFF30D158), Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                Row(Modifier.fillMaxWidth()) {
                    OverviewCell("Projects", projects, Icons.Default.Folder, Color(0xFFFF9F0A), Modifier.weight(1f))
                    OverviewVerticalDivider()
                    OverviewCell("Pending sync", pending, Icons.Default.CloudUpload, Color(0xFFAF52DE), Modifier.weight(1f))
                }
            }
        }
    }
    item {
        Text("Organize", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            DashboardActionRow("Projects", "Group related tags", Icons.Default.Folder, Color(0xFF0A84FF)) { navigate(Screen.PROJECTS) }
        }
    }
}

@Composable private fun StatusCard(status: NfcAvailability, openSettings: () -> Unit) {
    val title: String
    val detail: String
    val statusColor: Color
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    when (status) {
        NfcAvailability.READY -> { title = "NFC is ready"; detail = "Hold a tag near your phone to begin"; statusColor = Color(0xFF059669); icon = Icons.Default.CheckCircle }
        NfcAvailability.DISABLED -> { title = "NFC is turned off"; detail = "Enable NFC to read and write tags"; statusColor = Color(0xFFD97706); icon = Icons.Default.Warning }
        NfcAvailability.UNSUPPORTED -> { title = "NFC unavailable"; detail = "This device does not include NFC hardware"; statusColor = MaterialTheme.colorScheme.error; icon = Icons.Default.Info }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = statusColor.copy(alpha = .16f)) {
                Icon(icon, null, Modifier.padding(8.dp).size(21.dp), tint = statusColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            if (status == NfcAvailability.DISABLED) TextButton(openSettings) { Text("Enable") }
        }
    }
}

@Composable
private fun DashboardActionRow(label: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Surface(shape = RoundedCornerShape(9.dp), color = accent) {
        Icon(icon, null, Modifier.padding(7.dp).size(20.dp), tint = Color.White)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(8.dp))
    Icon(Icons.Default.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
}

@Composable private fun DashboardDivider() = HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))

@Composable
private fun OverviewCell(label: String, value: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier) = Row(
    modifier.padding(horizontal = 14.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, null, Modifier.size(20.dp), tint = accent)
    Spacer(Modifier.width(10.dp))
    Column {
        Text(value.toString(), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable private fun OverviewVerticalDivider() = Box(Modifier.padding(vertical = 11.dp).width(1.dp).height(42.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .35f)))

private data class PayloadChoice(val name: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val type: String)
private val choices = listOf(PayloadChoice("Link / Website", Icons.Default.Link, "uri"), PayloadChoice("Google Review", Icons.Default.Star, "google_review"), PayloadChoice("Social Media", Icons.Default.Share, "social"), PayloadChoice("Wi-Fi", Icons.Default.Wifi, "wifi"), PayloadChoice("Contact Card", Icons.Default.ContactPage, "contact"), PayloadChoice("Phone", Icons.Default.Phone, "tel"), PayloadChoice("Email", Icons.Default.Email, "mailto"), PayloadChoice("SMS", Icons.Default.Sms, "sms"), PayloadChoice("Location", Icons.Default.LocationOn, "geo"), PayloadChoice("Text", Icons.Default.TextFields, "text"), PayloadChoice("Custom URI", Icons.Default.Code, "custom"), PayloadChoice("Multiple Records", Icons.Default.LibraryAdd, "multiple"))

private val writerLinkTypes = listOf(
    "all" to "All managed URLs",
    "messages" to "TapTag Messages", "invitations" to "Invitations",
    "business" to "Business pages", "menus" to "Menus",
    "products" to "Products", "pets" to "Pet pages",
    "memorials" to "Memorial pages", "personal" to "Personal pages",
    "pages" to "Other TapTag pages", "uncategorized" to "Unsorted links",
    "custom" to "Custom website URL",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WriterUrlChoices(
    links: List<WriterManagedUrl>, category: String?, selected: WriterManagedUrl?,
    search: String, onSearch: (String) -> Unit,
    onCategory: (String) -> Unit, onLink: (WriterManagedUrl) -> Unit,
) {
    var categoryExpanded by remember { mutableStateOf(false) }
    var linkExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
        OutlinedTextField(
            value = writerLinkTypes.firstOrNull { it.first == category }?.second ?: "Choose link type",
            onValueChange = {}, readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            label = { Text("1. Link type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
            writerLinkTypes.forEach { (type, label) ->
                val count = if (type == "all") links.size else links.count { it.sourceType == type }
                DropdownMenuItem(
                    text = { Text(if (type == "custom") label else "$label · $count") },
                    onClick = { onCategory(type); categoryExpanded = false; linkExpanded = false },
                )
            }
        }
    }
    if (category != null && category != "custom") {
        OutlinedTextField(
            value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search name, recipient, or code") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        val visible = filterWriterManagedUrls(links, category, search)
        ExposedDropdownMenuBox(expanded = linkExpanded, onExpandedChange = { linkExpanded = it }) {
            OutlinedTextField(
                value = selected?.let { "${it.name} · ${it.publicCode}" } ?: "Choose exact card URL",
                onValueChange = {}, readOnly = true,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                label = { Text("2. Card URL") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(linkExpanded) },
                shape = RoundedCornerShape(14.dp),
            )
            ExposedDropdownMenu(expanded = linkExpanded, onDismissRequest = { linkExpanded = false }) {
                if (visible.isEmpty()) DropdownMenuItem(
                    text = { Text(if (links.any { it.sourceType == category }) "No matching URLs" else "No URLs in this type yet") },
                    onClick = { linkExpanded = false }, enabled = false,
                )
                visible.forEach { item ->
                    DropdownMenuItem(
                        text = { Column {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(item.recipientName?.let { "For $it" }, item.publicCode,
                                when {
                                    item.status == "retired" -> "Retired · cannot write"
                                    item.linkStatus != "active" || item.status == "disabled" -> "Disabled · cannot write"
                                    item.status == "provisioning" -> "Ready to write"
                                    else -> "Already written"
                                }).joinToString(" · "),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } },
                        onClick = { onLink(item); linkExpanded = false },
                        leadingIcon = { Icon(Icons.Default.Nfc, null) },
                    )
                }
            }
        }
    }
}

@Composable private fun PayloadEditor(
    actionLabel: String = "WRITE TO NFC",
    allowProtection: Boolean = true,
    managedPickerEnabled: Boolean = false,
    siteOrigin: String = "",
    loadManagedUrls: suspend () -> List<WriterManagedUrl> = { emptyList() },
    prepareNextMessageCard: suspend (String, String) -> NextMessageCard? = { _, _ -> null },
    onWrite: (NfcPayload, ProtectionRequest) -> Unit,
) {
    var choice by remember { mutableStateOf<PayloadChoice?>(null) }
    if (choice == null) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                FeatureHero(
                    icon = Icons.Default.AutoAwesome,
                    eyebrow = "NFC STUDIO",
                    title = "Create a smart tag",
                    detail = "Choose an experience, customize its content, then add managed write protection if needed.",
                )
                Spacer(Modifier.height(22.dp))
                Text("Choose what the tag does", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-.2).sp)
                Text("You can review the required tag size before writing.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                    Column {
                        choices.forEachIndexed { index, c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { choice = c }.padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)) {
                                    Icon(c.icon, null, Modifier.padding(8.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(13.dp))
                                Text(c.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (index != choices.lastIndex) HorizontalDivider(Modifier.padding(start = 65.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .34f))
                        }
                    }
                }
            }
        }
        return
    }
    PayloadForm(choice!!, actionLabel, allowProtection, managedPickerEnabled, siteOrigin, loadManagedUrls, prepareNextMessageCard, { choice = null }, onWrite)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QuickWriteScreen(
    managedPickerEnabled: Boolean,
    siteOrigin: String,
    loadManagedUrls: suspend () -> List<WriterManagedUrl>,
    prepareNextMessageCard: suspend (String, String) -> NextMessageCard?,
    onWrite: (NfcPayload) -> Unit,
    openCreate: () -> Unit,
) {
    var customUrl by remember { mutableStateOf("") }
    var managedUrls by remember { mutableStateOf<List<WriterManagedUrl>>(emptyList()) }
    var selectedLinkId by remember { mutableStateOf<String?>(null) }
    var linkCategory by remember { mutableStateOf<String?>("all") }
    var linkSearch by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var preparedOwnerCode by remember { mutableStateOf<String?>(null) }
    suspend fun refreshUrls(preferredPath: String? = null) {
        if (!managedPickerEnabled) return
        loading = true
        loadError = null
        runCatching { loadManagedUrls() }
            .onSuccess { latest ->
                managedUrls = latest
                if (preferredPath != null) {
                    val prepared = latest.firstOrNull { it.permanentPath.equals(preferredPath, ignoreCase = true) }
                    selectedLinkId = prepared?.linkId
                    linkCategory = prepared?.sourceType
                } else if (selectedLinkId != null && latest.none { it.linkId == selectedLinkId }) {
                    selectedLinkId = null
                }
            }
            .onFailure { loadError = it.message ?: "Managed URLs could not be loaded." }
        loading = false
    }
    LaunchedEffect(managedPickerEnabled) { refreshUrls() }
    val scope = rememberCoroutineScope()
    val selected = managedUrls.firstOrNull { it.linkId == selectedLinkId && (linkCategory == "all" || it.sourceType == linkCategory) }
    val selectedUrl = selected?.let { resolveWriterUrl(siteOrigin, it.permanentPath) }
    val url = selected?.takeIf(::writerUrlReadyToWrite)?.let { selectedUrl }
        ?: if (!managedPickerEnabled || linkCategory == "custom") customUrl else ""
    val normalized = normalizeWebUrl(url)
    val valid = normalized != null
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            FeatureHero(
                icon = Icons.Default.Bolt,
                eyebrow = "FAST LANE",
                title = "Write a link in seconds",
                detail = "Paste one secure web address, tap Write, and hold the phone near your NFC tag.",
                accent = Color(0xFFFF9F0A),
            )
        }
        item {
            Text("Destination", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(9.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (managedPickerEnabled) {
                        WriterUrlChoices(managedUrls, linkCategory, selected, linkSearch, { linkSearch = it },
                            { category -> linkCategory = category; selectedLinkId = null; linkSearch = ""; preparedOwnerCode = null },
                            { item -> selectedLinkId = item.linkId })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    loading -> "Loading latest managed URLs…"
                                    loadError != null -> loadError.orEmpty()
                                    managedUrls.isEmpty() -> "No managed URLs yet. You can still write a custom website."
                                    linkCategory == null -> "Choose a link type, then the exact card URL."
                                    selected != null && !writerUrlReadyToWrite(selected) -> writerUrlUnavailableReason(selected)
                                    else -> "Check the card name and code before writing."
                                },
                                Modifier.weight(1f),
                                color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            IconButton(onClick = { scope.launch { refreshUrls() } }, enabled = !loading) {
                                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "Refresh managed URLs")
                            }
                        }
                        if (selected?.sourceType == "messages" && selected.status == "active" && !selected.physicalUid.isNullOrBlank()) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching { prepareNextMessageCard(resolveWriterUrl(siteOrigin, selected.permanentPath), selected.physicalUid) }
                                        .onSuccess { next ->
                                            preparedOwnerCode = next?.currentOwnerCode
                                            if (next != null) refreshUrls(next.nextUrl)
                                            else loadError = "This URL is not a published write-on-tap message."
                                        }
                                        .onFailure { loadError = it.message ?: "Next message card could not be prepared." }
                                    loading = false
                                }
                            }, enabled = !loading) { Text("GENERATE NEXT CARD URL") }
                        }
                        preparedOwnerCode?.let { code ->
                            Text("Shared owner code for this owner: $code. Save it now; it opens every linked card's private inbox.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedTextField(
                        value = selectedUrl ?: url,
                        onValueChange = { customUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (selected == null) "Custom website URL" else "Selected TapTag URL") },
                        placeholder = { Text("https://example.com") },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        singleLine = true,
                        readOnly = selected != null || (managedPickerEnabled && linkCategory != "custom"),
                        isError = selected != null && !writerUrlReadyToWrite(selected) || (url.isNotBlank() && !valid),
                        supportingText = {
                            Text(
                                when {
                                    selected != null && !writerUrlReadyToWrite(selected) -> writerUrlUnavailableReason(selected)
                                    selected != null -> "This URL came from the superadmin website and will be verified after writing."
                                    managedPickerEnabled && linkCategory == null -> "Choose a link type above to avoid writing the wrong card."
                                    managedPickerEnabled && linkCategory != "custom" -> "Choose the exact card URL above."
                                    url.isBlank() || valid -> "Enter an HTTPS website, or HTTP for local testing."
                                    else -> "Enter a complete HTTP or HTTPS address."
                                }
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                    if (valid) {
                        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF34C759).copy(alpha = .12f)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF248A3D), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column { Text("Ready to write", fontWeight = FontWeight.SemiBold); Text(normalized.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                    Button(
                        onClick = { normalized?.let { onWrite(NfcPayload.UriPayload(it)) } },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = valid,
                        shape = RoundedCornerShape(15.dp),
                    ) { Icon(Icons.Default.Nfc, null); Spacer(Modifier.width(8.dp)); Text("WRITE LINK NOW", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) { Text("Need more control?", fontWeight = FontWeight.SemiBold); Text("Use Create for Wi-Fi, contacts, text, multiple records, or write protection.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(openCreate) { Text("OPEN") }
                }
            }
        }
    }
}

@Composable private fun FeatureHero(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    eyebrow: String,
    title: String,
    detail: String,
    accent: Color = MaterialTheme.colorScheme.primary,
) = Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = accent.copy(alpha = .10f),
) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(13.dp), color = accent) { Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = Color.White) }
        Text(eyebrow, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable private fun PayloadForm(
    choice: PayloadChoice,
    actionLabel: String,
    allowProtection: Boolean,
    managedPickerEnabled: Boolean,
    siteOrigin: String,
    loadManagedUrls: suspend () -> List<WriterManagedUrl>,
    prepareNextMessageCard: suspend (String, String) -> NextMessageCard?,
    back: () -> Unit,
    onWrite: (NfcPayload, ProtectionRequest) -> Unit,
) {
    var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; var d by remember { mutableStateOf("") }; var security by remember { mutableStateOf("WPA2") }; var showSecret by remember { mutableStateOf(false) }; var protect by remember { mutableStateOf(false) }; var customMode by remember { mutableStateOf(false) }; var customCredential by remember { mutableStateOf("") }; var confirmCredential by remember { mutableStateOf("") }; var showCredential by remember { mutableStateOf(false) }
    val payload = runCatching<NfcPayload> { when (choice.type) {
        "uri", "google_review", "social" -> NfcPayload.UriPayload(requireNotNull(normalizeWebUrl(a)), choice.type)
        "wifi" -> NfcPayload.Wifi(a, security, b)
        "contact" -> NfcPayload.Contact(a, b, c, d)
        "tel" -> NfcPayload.UriPayload("tel:${a.trim()}", "phone")
        "mailto" -> NfcPayload.UriPayload("mailto:${a.trim()}?subject=${android.net.Uri.encode(b)}&body=${android.net.Uri.encode(c)}", "email")
        "sms" -> NfcPayload.UriPayload("sms:${a.trim()}?body=${android.net.Uri.encode(b)}", "sms")
        "geo" -> NfcPayload.UriPayload("geo:${a.trim()},${b.trim()}", "location")
        "text" -> NfcPayload.Text(a)
        "custom" -> NfcPayload.UriPayload(a.trim(), "custom")
        else -> NfcPayload.Multiple(listOf(NfcPayload.UriPayload(a.trim()), NfcPayload.Text(b)))
    } }.getOrNull()
    val valid = a.isNotBlank() && when (choice.type) { "uri", "google_review", "social" -> normalizeWebUrl(a) != null; "custom" -> validateUri(a); "geo", "multiple" -> b.isNotBlank(); "wifi" -> security == "OPEN" || b.isNotBlank(); else -> true }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(choice.name, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("A proper standard NDEF record will be created.") }
        item { when (choice.type) {
            "wifi" -> {
                Field(a, { a = it }, "SSID")
                WifiSecurityDropdown(security) { security = it; if (it == "OPEN") b = "" }
                if (security != "OPEN") OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ showSecret = !showSecret }) { Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
                Text(
                    if (security == "WPA3") "WPA3 uses SAE. It is stored for compatible credential readers; legacy NFC auto-connect is limited to WPA2."
                    else if (security == "WPA2_WPA3") "Transition mode uses the broadly compatible WPA2 credential and can join WPA2/WPA3 mixed networks."
                    else "The password is written to the tag but is never uploaded in plaintext.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            "contact" -> { Field(a, { a = it }, "Name"); Field(b, { b = it }, "Phone"); Field(c, { c = it }, "Email"); Field(d, { d = it }, "Website") }
            "mailto" -> { Field(a, { a = it }, "Email address"); Field(b, { b = it }, "Subject"); Field(c, { c = it }, "Message") }
            "sms" -> { Field(a, { a = it }, "Phone number"); Field(b, { b = it }, "Message") }
            "geo" -> { Field(a, { a = it }, "Latitude"); Field(b, { b = it }, "Longitude") }
            "multiple" -> { Field(a, { a = it }, "URI record"); Field(b, { b = it }, "Text record"); Text("Advanced mode writes both records in one NDEF message.") }
            "text" -> {
                Field(a, { a = it }, "Plain text", false)
                Text("Stored as a standard NFC Forum RTD_TEXT record. Some Android devices have no default screen for text tags outside TapTag; that is normal device behavior.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "uri" -> ManagedLinkSelector(
                value = a,
                onValueChange = { a = it },
                enabled = managedPickerEnabled,
                siteOrigin = siteOrigin,
                loadManagedUrls = loadManagedUrls,
                prepareNextMessageCard = prepareNextMessageCard,
            )
            else -> Field(a, { a = it }, if (choice.type == "tel") "Phone number" else "URI")
        } }
        if (allowProtection) item { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Column { Row(Modifier.fillMaxWidth().clickable { protect = !protect }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Protect against unauthorized rewriting", fontWeight = FontWeight.SemiBold); Text(if (choice.type == "text") "Protection preserves RTD_TEXT; outside-app display still depends on Android." else "Customers can still tap and read this NFC normally.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(protect, { protect = it }) }; if (protect) { HorizontalDivider(Modifier.padding(start = 50.dp)); Text("PASSWORD MODE", Modifier.padding(start = 15.dp, top = 12.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); ProtectionModeRow(!customMode, "Auto-generated", "Recommended • unique credential managed by TapTag") { customMode = false }; ProtectionModeRow(customMode, "Custom password", "Advanced / testing • exact chip credential") { customMode = true }; if (customMode) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { OutlinedTextField(customCredential, { customCredential = it.filter { ch -> ch.isLetterOrDigit() || ch in " :-" }.take(17) }, Modifier.fillMaxWidth(), label = { Text("Credential (12 hex characters)") }, visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true); OutlinedTextField(confirmCredential, { confirmCredential = it.filter { ch -> ch.isLetterOrDigit() || ch in " :-" }.take(17) }, Modifier.fillMaxWidth(), label = { Text("Confirm credential") }, visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showCredential, { showCredential = it }); Text("Show credential") }; Text("Format: 8 hex characters for the 4-byte PWD, then 4 hex characters for the 2-byte PACK. This is the NFC hardware credential, not your TapTag account password.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } } }
        item { val customValid = !protect || !customMode || (customCredential == confirmCredential && runCatching { NtagCredential.fromHex(customCredential) }.isSuccess); payload?.let { if (it is NfcPayload.Text) { Text("Text size: ${it.byteSize()} bytes"); Text("NFC capacity: Scan tag to check", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } else Text("Required NDEF size: ${runCatching { it.byteSize() }.getOrDefault(0)} bytes") }; Button({ payload?.let { value -> val request = when { !protect -> ProtectionRequest.None; !customMode -> ProtectionRequest.AutoGenerated; else -> ProtectionRequest.Custom(NtagCredential.fromHex(customCredential)) }; customCredential = ""; confirmCredential = ""; onWrite(value, request) } }, Modifier.fillMaxWidth().height(56.dp), enabled = valid && payload != null && customValid) { Icon(Icons.Default.Nfc, null); Spacer(Modifier.width(8.dp)); Text(actionLabel) }; TextButton(back, Modifier.fillMaxWidth()) { Text("CHOOSE ANOTHER TYPE") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ManagedLinkSelector(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    siteOrigin: String,
    loadManagedUrls: suspend () -> List<WriterManagedUrl>,
    prepareNextMessageCard: suspend (String, String) -> NextMessageCard?,
) {
    if (!enabled) {
        Field(value, onValueChange, "Website URL")
        return
    }
    var links by remember { mutableStateOf<List<WriterManagedUrl>>(emptyList()) }
    var selectedLinkId by remember { mutableStateOf<String?>(null) }
    var customDraft by remember { mutableStateOf(value) }
    var category by remember { mutableStateOf<String?>("all") }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparingNext by remember { mutableStateOf(false) }
    var preparedOwnerCode by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(preferredPath: String? = null) {
        loading = true
        error = null
        runCatching { loadManagedUrls() }
            .onSuccess { latest ->
                links = latest
                if (preferredPath != null) {
                    val next = latest.firstOrNull { it.permanentPath.equals(preferredPath, ignoreCase = true) }
                    if (next != null) {
                        category = next.sourceType
                        selectedLinkId = next.linkId
                        onValueChange(resolveWriterUrl(siteOrigin, next.permanentPath))
                    } else {
                        error = "Next card URL was created but is not in the list yet. Refresh and search for it."
                    }
                } else if (selectedLinkId != null && latest.none { it.linkId == selectedLinkId }) {
                    selectedLinkId = null
                    onValueChange("")
                }
            }
            .onFailure { error = it.message ?: "Managed URLs could not be loaded." }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }
    val selected = links.firstOrNull { it.linkId == selectedLinkId && (category == "all" || it.sourceType == category) }

    WriterUrlChoices(links, category, selected, search, { search = it },
        { chosen ->
            if (category == "custom") customDraft = value
            category = chosen
            selectedLinkId = null
            search = ""
            preparedOwnerCode = null
            onValueChange(if (chosen == "custom") customDraft else "")
        },
        { item ->
            selectedLinkId = item.linkId
            preparedOwnerCode = null
            onValueChange(if (writerUrlReadyToWrite(item)) resolveWriterUrl(siteOrigin, item.permanentPath) else "")
        })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                loading -> "Loading latest managed URLs…"
                error != null -> error.orEmpty()
                links.isEmpty() -> "No managed URLs yet. Choose Custom website URL above."
                category == null -> "Choose the link type first, then the exact card."
                selected != null && !writerUrlReadyToWrite(selected) -> writerUrlUnavailableReason(selected)
                else -> "Check the card name and code before writing."
            },
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, "Refresh managed URLs")
        }
    }
    if (selected != null && selected.sourceType == "messages" && selected.linkStatus == "active" &&
        selected.status == "active" && !writerUrlReadyToWrite(selected)) {
        if (!selected.physicalUid.isNullOrBlank()) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        preparingNext = true
                        error = null
                        runCatching { prepareNextMessageCard(resolveWriterUrl(siteOrigin, selected.permanentPath), selected.physicalUid) }
                            .onSuccess { next ->
                                if (next == null) error = "This message does not support another card URL."
                                else {
                                    preparedOwnerCode = next.currentOwnerCode
                                    refresh(next.nextUrl)
                                }
                            }
                            .onFailure { error = it.message ?: "Next card URL could not be generated." }
                        preparingNext = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !preparingNext && !loading,
            ) { Text(if (preparingNext) "GENERATING NEXT URL…" else "GENERATE NEXT CARD URL") }
        } else {
            Text("This URL is already used, but its NFC card ID is missing. Check the card record before creating the next URL.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
    preparedOwnerCode?.let { code ->
        Text("Shared owner code: $code. Save it now for this owner's private inbox.", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }
    OutlinedTextField(
        value = if (selected != null && !writerUrlReadyToWrite(selected)) resolveWriterUrl(siteOrigin, selected.permanentPath) else value,
        onValueChange = { if (category == "custom") { customDraft = it; onValueChange(it) } },
        modifier = Modifier.fillMaxWidth(),
        readOnly = category != "custom",
        singleLine = true,
        label = { Text(if (selected == null) "Custom website URL" else "Selected TapTag URL") },
        placeholder = { Text("https://example.com") },
        leadingIcon = { Icon(Icons.Default.Link, null) },
        supportingText = { Text(when {
            selected != null && !writerUrlReadyToWrite(selected) -> writerUrlUnavailableReason(selected)
            selected != null -> "Verified after the NFC write completes."
            category == "custom" -> "Use HTTPS when deployed."
            else -> "Choose a card URL above to continue."
        }) },
    )
}

@Composable private fun ProtectionModeRow(selected: Boolean, title: String, detail: String, choose: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = choose).padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, choose); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

private data class WifiSecurityOption(val value: String, val label: String, val detail: String)

private val wifiSecurityOptions = listOf(
    WifiSecurityOption("WPA3", "WPA3 Personal", "SAE • strongest security"),
    WifiSecurityOption("WPA2_WPA3", "WPA2/WPA3 Transition", "Best compatibility for mixed-mode hotspots"),
    WifiSecurityOption("WPA2", "WPA2 Personal", "AES • recommended for older devices"),
    WifiSecurityOption("WPA", "WPA Personal", "Legacy security"),
    WifiSecurityOption("WEP", "WEP", "Legacy and insecure"),
    WifiSecurityOption("OPEN", "Open network", "No password"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WifiSecurityDropdown(value: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = wifiSecurityOptions.firstOrNull { it.value == value } ?: wifiSecurityOptions[2]
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text("Security type") },
            supportingText = { Text(selected.detail) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            wifiSecurityOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Column { Text(option.label); Text(option.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    onClick = { select(option.value); expanded = false },
                    leadingIcon = { if (option.value == value) Icon(Icons.Default.Check, null) },
                )
            }
        }
    }
}

@Composable private fun Field(value: String, change: (String) -> Unit, label: String, single: Boolean = true) = OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = single)

@Composable
private fun RewriteConfirmationScreen(source: TagSnapshot?, payload: NfcPayload?, back: () -> Unit, confirm: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    item { Text("Rewrite NFC", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp) }
    item { ContentComparisonCard("Current content", source?.records?.firstOrNull()?.let { recordLabel(it.type) } ?: "Unknown", source?.records?.joinToString("\n") { it.content }.orEmpty(), Color(0xFFFF9F0A)) }
    item { ContentComparisonCard("Replace with", payload?.let { payloadLabel(it.type) }.orEmpty(), payload?.let(::payloadDescription).orEmpty(), MaterialTheme.colorScheme.primary) }
    item {
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFF9F0A).copy(alpha = .14f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9F0A)); Spacer(Modifier.width(10.dp)); Text("The current NFC content will be replaced. The tag will remain rewritable.", fontSize = 13.sp) }
        }
    }
    if (source?.uid != null) item { Text("UID matching is used only to prevent accidental tag swaps. A tag UID is not a cryptographic identity.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    item { OutlinedButton(back, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(13.dp)) { Text("CANCEL") }; Spacer(Modifier.height(8.dp)); Button(confirm, Modifier.fillMaxWidth().height(54.dp), enabled = source?.writable == true && payload != null, shape = RoundedCornerShape(13.dp)) { Text("REWRITE NFC") } }
}

@Composable
private fun ContentComparisonCard(heading: String, type: String, content: String, accent: Color) = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(9.dp), color = accent) { Icon(Icons.Default.Nfc, null, Modifier.padding(7.dp).size(20.dp), tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column { Text(heading.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp); Text(type, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); if (content.isNotBlank()) Text(content, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable
private fun NfcOverlay(state: NfcUiState, engine: NfcEngine, payload: NfcPayload?, siteOrigin: String, done: () -> Unit) = Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
    if (state.isMock) MockModeBanner()
    Box(Modifier.weight(1f)) { if (state.stage == NfcStage.AWAITING_CONFIRMATION && payload != null) {
        val replacing = state.previousSnapshot != null
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Icon(if (replacing) Icons.Default.Warning else Icons.Default.TextFields, null, Modifier.size(42.dp), if (replacing) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.primary); Spacer(Modifier.height(10.dp)); Text(if (replacing) "NFC tag already has content" else "Confirm Plain Text write", fontSize = 27.sp, fontWeight = FontWeight.Bold) }
            if (replacing) item { ContentComparisonCard("Current", state.snapshot?.records?.firstOrNull()?.let { recordLabel(it.type) } ?: "Unknown", state.snapshot?.records?.joinToString("\n") { it.content }.orEmpty(), Color(0xFFFF9F0A)) }
            item { ContentComparisonCard("New", payloadLabel(payload.type), payloadDescription(payload), MaterialTheme.colorScheme.primary) }
            item { Text(if (payload is NfcPayload.Text) "Text size: ${payload.byteSize()} bytes\nNFC capacity: ${state.snapshot?.capacity ?: "Unknown"} bytes" else "Required NDEF size: ${payload.byteSize()} bytes\nNFC capacity: ${state.snapshot?.capacity ?: "Unknown"} bytes", fontWeight = FontWeight.SemiBold) }
            item { Text(if (replacing) "Writing this content will replace the current NFC content." else "TapTag will write a standard RTD_TEXT record after you confirm and re-tap this tag. Android may not display text automatically outside TapTag if no text-tag handler is installed.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
            item { OutlinedButton({ engine.cancel(); done() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(13.dp)) { Text("CANCEL") }; Spacer(Modifier.height(8.dp)); Button(if (replacing) engine::confirmReplacement else engine::confirmInspectedWrite, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(13.dp)) { Text(if (replacing) "REPLACE CONTENT" else "CONTINUE TO WRITE") } }
        }
    } else if (state.isCriticalOperation || state.stage in setOf(NfcStage.READY, NfcStage.DETECTED, NfcStage.INTERRUPTED, NfcStage.CONNECTION_LOST, NfcStage.RECOVERY_REQUIRED, NfcStage.SAFE_FAILURE)) {
        CriticalOperationContent(state, engine, done)
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val assignmentNeedsAttention = state.stage == NfcStage.SUCCESS && state.provisioningError != null
            Icon(if (assignmentNeedsAttention || state.stage == NfcStage.ERROR) Icons.Default.Error else if (state.stage == NfcStage.SUCCESS) Icons.Default.CheckCircle else Icons.Default.Nfc, null, Modifier.size(94.dp), if (assignmentNeedsAttention || state.stage == NfcStage.ERROR) MaterialTheme.colorScheme.error else if (state.stage == NfcStage.SUCCESS) Color(0xFF30D158) else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(22.dp))
            Text(when (state.stage) {
                NfcStage.READY -> when (state.operation) {
                    "read" -> "Ready to read"
                    "rewrite" -> "Ready to rewrite"
                    "erase" -> "Ready to erase"
                    "verify" -> "Ready to verify"
                    "permanent_lock" -> "Ready to lock"
                    "protection_enabled", "protection_removed", "manual_authentication" -> "Ready for security check"
                    else -> "Ready to write"
                }
                NfcStage.SUCCESS -> if (assignmentNeedsAttention) "Card written · action needed" else "NFC READY"
                else -> state.stage.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
            }, fontSize = 27.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(state.message, Modifier.padding(top = 8.dp, bottom = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (state.stage == NfcStage.SUCCESS && !assignmentNeedsAttention) Surface(shape = RoundedCornerShape(50), color = Color(0xFF30D158).copy(alpha = .14f)) { Text("YOU CAN NOW REMOVE THE TAG", Modifier.padding(horizontal = 15.dp, vertical = 9.dp), color = Color(0xFF169B45), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp) }
            if (state.stage == NfcStage.SUCCESS && state.customerAccessCode != null) {
                Spacer(Modifier.height(18.dp))
                Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("CUSTOMER ACCESS CODE",fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,color=MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(state.customerAccessCode,fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
                        Text(if (state.nextMessageUrl != null) "This TK code opens the page editor. The private message inbox uses its separate TM owner code." else "Give this code to the NFC card owner. It opens the editor for ${state.permanentUrl ?: "this TapTag URL"}.",Modifier.padding(top=8.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton({clipboard.setText(AnnotatedString(state.customerAccessCode))},Modifier.padding(top=12.dp)) { Icon(Icons.Default.ContentCopy,null,Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("COPY ACCESS CODE") }
                    }
                }
            }
            if (state.stage == NfcStage.SUCCESS && state.messageOwnerCode != null) {
                Spacer(Modifier.height(14.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PRIVATE MESSAGE OWNER CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text(state.messageOwnerCode, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        Text("Save this TM code now. It opens this owner's inbox on every linked card.", Modifier.padding(top = 8.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton({ clipboard.setText(AnnotatedString(state.messageOwnerCode)) }, Modifier.padding(top = 10.dp)) { Text("COPY OWNER CODE") }
                    }
                }
            }
            if (state.stage == NfcStage.SUCCESS && state.nextMessageUrl != null) {
                Spacer(Modifier.height(14.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NEXT NFC CARD URL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text(state.nextMessageUrl, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        Text("Same owner and TM code, but a new card URL and private inbox. WRITE ANOTHER uses this URL automatically.", Modifier.padding(top = 8.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton({ clipboard.setText(AnnotatedString(resolveWriterUrl(siteOrigin, state.nextMessageUrl))) }, Modifier.padding(top = 10.dp)) { Text("COPY NEXT URL") }
                    }
                }
            }
            if (state.stage == NfcStage.SUCCESS && state.nextCardError != null) {
                Spacer(Modifier.height(14.dp))
                Text("This card was activated, but the next message URL could not be prepared: ${state.nextCardError}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (state.stage == NfcStage.SUCCESS && state.provisioningError != null) {
                Spacer(Modifier.height(14.dp))
                Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer) { Text("The card now contains the new content, but its TapTag assignment was not updated. Read this card, then choose Rewrite with the same new URL to retry activation. ${state.provisioningError}",Modifier.padding(14.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onErrorContainer,textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
            }
            state.requiredBytes?.let { Text("Required: $it bytes • Available: ${state.snapshot?.capacity ?: "scan tag"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.stage == NfcStage.SUCCESS) Text(buildString { append(if (state.isMock) "🧪 MOCK APPLICATION FLOW VERIFIED" else "✓ Physical read-back verified"); if (state.snapshot?.securityState == "protected") append(if (state.isMock) "\n🧪 MOCK PROTECTION VERIFIED\nPublic simulated reading: Allowed" else "\n🔐 Write protected • Public reading allowed\nPassword mode: ${state.credentialMode ?: "Managed"}"); append(if (state.isMock) "\nNo physical NFC or security operation occurred." else if (assignmentNeedsAttention) "\nThe TapTag URL is not active for this card yet." else "\nCloud changes are saved locally and queued for sync.") }, Modifier.padding(top = 14.dp), color = if (assignmentNeedsAttention) MaterialTheme.colorScheme.error else if (state.isMock) Color(0xFFFF9F0A) else Color(0xFF30D158), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            when (state.stage) {
                NfcStage.SUCCESS -> { if (!assignmentNeedsAttention && state.operation == "write" && payload != null) { Button({ engine.begin(PendingNfcOperation.InspectThenWrite(state.nextMessageUrl?.let { NfcPayload.UriPayload(resolveWriterUrl(siteOrigin, it)) } ?: payload)) }, Modifier.fillMaxWidth()) { Text(if (state.nextMessageUrl != null) "WRITE NEXT CARD" else "WRITE ANOTHER") }; Spacer(Modifier.height(8.dp)) }; OutlinedButton(done, Modifier.fillMaxWidth()) { Text(if (assignmentNeedsAttention) "CLOSE" else "DONE") } }
                NfcStage.ERROR -> { Button(engine::retry, Modifier.fillMaxWidth()) { Text(if (state.message.startsWith("DIFFERENT NFC TAG")) "SCAN ORIGINAL TAG" else "TRY AGAIN") }; TextButton({ engine.cancel(); done() }) { Text("CANCEL") } }
                else -> TextButton({ engine.cancel(); done() }) { Text("CANCEL") }
            }
        }
    } }
    }
}

@Composable
private fun CriticalOperationContent(state: NfcUiState, engine: NfcEngine, done: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.stage) {
        if (state.stage == NfcStage.DETECTED) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val critical = state.isCriticalOperation
    val terminalWarning = state.stage in setOf(NfcStage.INTERRUPTED, NfcStage.CONNECTION_LOST, NfcStage.RECOVERY_REQUIRED, NfcStage.SAFE_FAILURE)
    val title = when (state.stage) {
        NfcStage.READY -> "Hold your NFC tag near the back of your phone"
        NfcStage.DETECTED -> "NFC detected"
        NfcStage.AUTHENTICATING -> "Authenticating…"
        NfcStage.WRITING -> "Writing to NFC…"
        NfcStage.VERIFYING -> "Verifying…"
        NfcStage.APPLYING_PROTECTION -> "Applying write protection…"
        NfcStage.VERIFYING_PROTECTION -> "Verifying protection…"
        NfcStage.ERASING -> "Erasing NFC…"
        NfcStage.INTERRUPTED -> "NFC OPERATION INTERRUPTED"
        NfcStage.CONNECTION_LOST -> "TAG CONNECTION LOST"
        NfcStage.RECOVERY_REQUIRED -> "RECOVERY CHECK REQUIRED"
        NfcStage.SAFE_FAILURE -> if (state.contentVerified) "Content safe — security unconfirmed" else "Operation needs attention"
        else -> state.stage.name.replace('_', ' ')
    }
    val detail = when (state.stage) {
        NfcStage.READY -> "READY TO SCAN"
        NfcStage.AUTHENTICATING -> "Checking authorization without displaying the credential"
        NfcStage.WRITING -> "Keep the tag firmly against the back of your phone"
        NfcStage.VERIFYING -> "Reading the tag back to confirm your data"
        NfcStage.APPLYING_PROTECTION -> "Keep the tag in place while security is configured"
        NfcStage.VERIFYING_PROTECTION -> "Confirming the physical security state"
        NfcStage.ERASING -> "Keep the tag in place until the erase is verified"
        NfcStage.INTERRUPTED -> "The app was left before the operation could be safely confirmed. Keep the same NFC tag against your phone and scan again."
        NfcStage.CONNECTION_LOST -> "Do not move the NFC tag while writing. Keep the same tag in place, then rescan."
        NfcStage.RECOVERY_REQUIRED -> state.message
        NfcStage.SAFE_FAILURE -> state.message
        else -> state.message
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.isMock) {
            Surface(shape = RoundedCornerShape(50), color = Color(0xFFFF9F0A).copy(alpha = .16f)) {
                Text("🧪 MOCK NFC · APPLICATION TEST ONLY", Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = Color(0xFFFF9F0A), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
            Spacer(Modifier.height(18.dp))
        }
        if (critical) {
            Text("DO NOT MOVE YOUR PHONE", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Text("Keep the NFC tag firmly against the back of your phone.", Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Do not remove the tag or leave TapTag until the operation is complete.", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(22.dp))
        }
        NfcStatusVisual(state.stage, terminalWarning)
        Spacer(Modifier.height(26.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(detail, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (critical) {
            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Nfc, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text("KEEP TAG IN PLACE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                }
            }
        }
        if (state.operation in setOf("write", "rewrite", "erase", "protection_enabled")) {
            Spacer(Modifier.height(24.dp))
            OperationProgress(state)
        }
        if (state.stage == NfcStage.SAFE_FAILURE && state.contentWritten) {
            Spacer(Modifier.height(22.dp))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("CONTENT", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .8.sp)
                    Text("${if (state.contentWritten) "✓" else "○"} Written\n${if (state.contentVerified) "✓" else "○"} Verified", color = if (state.contentVerified) Color(0xFF30D158) else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.protectionRequested) {
                        HorizontalDivider()
                        Text("SECURITY", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .8.sp)
                        Text(if (state.protectionVerified) "✓ Write protection verified" else "⚠ Protection not confirmed", color = if (state.protectionVerified) Color(0xFF30D158) else Color(0xFFFF9F0A), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        when (state.stage) {
            NfcStage.INTERRUPTED, NfcStage.CONNECTION_LOST -> {
                Spacer(Modifier.height(24.dp)); Button(engine::retry, Modifier.fillMaxWidth().height(54.dp)) { Text("RESCAN TAG") }
            }
            NfcStage.RECOVERY_REQUIRED -> {
                Spacer(Modifier.height(24.dp)); Button(engine::retry, Modifier.fillMaxWidth().height(54.dp)) { Text(if (state.recoveryRetryAllowed) "CONTROLLED RETRY" else "RESCAN TAG") }
                TextButton(done) { Text("LEAVE RECOVERY") }
            }
            NfcStage.SAFE_FAILURE -> {
                Spacer(Modifier.height(24.dp)); if (state.recoveryRetryAllowed) Button(engine::retry, Modifier.fillMaxWidth().height(54.dp)) { Text("RETRY SAFELY") }
                OutlinedButton(done, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("DONE") }
            }
            NfcStage.READY -> { Spacer(Modifier.height(22.dp)); TextButton({ engine.cancel(); done() }) { Text("CANCEL BEFORE WRITE") } }
            else -> Unit
        }
        if (state.isMock) Text("Mock lifecycle testing does not prove physical NFC reliability.", Modifier.padding(top = 18.dp), color = Color(0xFFFF9F0A), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun NfcStatusVisual(stage: NfcStage, warning: Boolean) {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val transition = rememberInfiniteTransition(label = "nfc-status")
    val pulse by transition.animateFloat(
        initialValue = .86f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(tween(if (animationsEnabled) 1100 else 1, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nfc-pulse",
    )
    val color = if (warning) Color(0xFFFF9F0A) else if (stage == NfcStage.DETECTED) Color(0xFF30D158) else MaterialTheme.colorScheme.primary
    Box(Modifier.size(142.dp), contentAlignment = Alignment.Center) {
        repeat(3) { index ->
            val factor = if (animationsEnabled) pulse + index * .16f else 1f + index * .16f
            Surface(Modifier.size((88 + index * 18).dp).graphicsLayer { scaleX = factor; scaleY = factor; alpha = (0.18f - index * .04f).coerceAtLeast(.05f) }, shape = RoundedCornerShape(50), color = color) {}
        }
        Surface(Modifier.size(86.dp), shape = RoundedCornerShape(26.dp), color = color.copy(alpha = .16f)) {
            Box(contentAlignment = Alignment.Center) { Icon(if (warning) Icons.Default.Warning else if (stage == NfcStage.DETECTED) Icons.Default.CheckCircle else Icons.Default.Nfc, null, Modifier.size(42.dp), tint = color) }
        }
    }
}

@Composable
private fun OperationProgress(state: NfcUiState) {
    val steps = when (state.operation) {
        "erase" -> listOf("Detect", "Erase", "Verify", "Done")
        "protection_enabled" -> listOf("Detect", "Protect", "Verify", "Done")
        else -> buildList { add("Detect"); add("Write"); add("Verify"); if (state.protectionRequested) add("Protect"); add("Done") }
    }
    val active = when (state.stage) {
        NfcStage.READY -> 0
        NfcStage.DETECTED, NfcStage.CHECKING, NfcStage.AUTHENTICATING -> 0
        NfcStage.WRITING, NfcStage.ERASING -> 1
        NfcStage.VERIFYING -> 2
        NfcStage.APPLYING_PROTECTION, NfcStage.VERIFYING_PROTECTION -> steps.indexOf("Protect").coerceAtLeast(1)
        NfcStage.SUCCESS -> steps.lastIndex
        else -> when { state.protectionVerified -> steps.lastIndex; state.contentVerified -> steps.indexOf("Verify").coerceAtLeast(0); state.contentWritten -> 1; else -> 0 }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        steps.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(Modifier.size(25.dp), shape = RoundedCornerShape(50), color = when { index < active -> Color(0xFF30D158); index == active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant }) {
                    Box(contentAlignment = Alignment.Center) { Text(if (index < active) "✓" else if (index == active) "●" else "○", fontSize = 11.sp, color = if (index <= active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(label, Modifier.padding(top = 5.dp), fontSize = 10.sp, color = if (index <= active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NfcOperationPanel(state: NfcUiState, engine: NfcEngine, security: () -> Unit, rewrite: (TagSnapshot) -> Unit, start: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var confirmErase by remember { mutableStateOf<TagSnapshot?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val snapshot = state.snapshot
        if (snapshot == null) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.Nfc, null, Modifier.padding(24.dp).size(64.dp), MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(22.dp))
            Text(if (state.stage == NfcStage.IDLE) "Ready to scan" else state.stage.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            Text(if (state.stage == NfcStage.IDLE) "Hold the top of your phone near an NFC tag." else state.message, Modifier.padding(top = 8.dp, bottom = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (state.stage == NfcStage.IDLE) Button(start, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(13.dp)) { Text("SCAN TAG", fontWeight = FontWeight.SemiBold) }
            if (state.stage == NfcStage.ERROR) Button(start, Modifier.fillMaxWidth()) { Text("TRY AGAIN") }
            return@Column
        }

        Text("NFC tag", Modifier.fillMaxWidth(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                if (snapshot.isMock) Info("Environment", "🧪 MOCK — not a physical tag")
                Info("Type", snapshot.tagType)
                Info("Status", when { snapshot.writable == false -> "🔒 PERMANENTLY READ-ONLY"; snapshot.securityState == "protected" -> "🔐 MANAGED WRITE PROTECTION"; snapshot.securityState == "unprotected" -> "🔓 UNPROTECTED"; else -> "UNKNOWN" })
                Info("Protection", snapshot.securityVariant?.let { "$it • ${snapshot.securityState.replace('_', ' ')}" } ?: (snapshot.securityReason ?: "Password protection unavailable"))
                Info("Capacity", "${snapshot.capacity ?: "Unknown"} bytes")
                Info("UID", snapshot.uid ?: "Not exposed")
            }
        }
        Spacer(Modifier.height(18.dp))
        val plainText = snapshot.records.singleOrNull()?.takeIf { it.type == "Text" }?.content
        Text(if (plainText != null) "Plain Text" else "Current content", Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            if (plainText != null) SelectionContainer { Text("\"$plainText\"", Modifier.fillMaxWidth().padding(18.dp), fontSize = 17.sp, lineHeight = 24.sp) }
            else Column { if (snapshot.records.isEmpty()) Info("Content", "Blank tag") else snapshot.records.forEach { Info(it.type, it.content.ifBlank { "Empty" }) } }
        }
        Spacer(Modifier.height(18.dp))
        val openUri = snapshot.records.firstOrNull { it.type == "URI" }?.content
        if (plainText != null) Button({ clipboard.setText(AnnotatedString(plainText)) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(13.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("COPY TEXT") }
        else Button({ if (openUri != null) runCatching { uriHandler.openUri(openUri) } }, Modifier.fillMaxWidth().height(50.dp), enabled = openUri != null, shape = RoundedCornerShape(13.dp)) { Text("OPEN CONTENT") }
        OutlinedButton({ rewrite(snapshot) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), enabled = snapshot.writable == true, shape = RoundedCornerShape(13.dp)) { Text("REWRITE") }
        OutlinedButton({ snapshot.rawMessage?.let { engine.begin(PendingNfcOperation.VerifySnapshot(it, snapshot.uid)) } }, Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), enabled = snapshot.rawMessage != null, shape = RoundedCornerShape(13.dp)) { Text("VERIFY") }
        OutlinedButton(security, Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = RoundedCornerShape(13.dp)) { Text("SECURITY") }
        TextButton({ confirmErase = snapshot }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = snapshot.writable == true) { Text("ERASE", color = if (snapshot.writable == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (snapshot.writable == false) Text("This NFC tag cannot be replaced or erased. TapTag does not offer an unlock action for permanently read-only tags.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    confirmErase?.let { target -> AlertDialog({ confirmErase = null }, { Button({ confirmErase = null; engine.begin(PendingNfcOperation.Erase(target.uid)) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("ERASE CONTENT") } }, title = { Text("Erase NDEF content?") }, text = { Text("Tap the same physical tag again. TapTag will authenticate it when managed protection is enabled, clear only writable NDEF user content, and verify the result.") }, dismissButton = { TextButton({ confirmErase = null }) { Text("Cancel") } }) }
}

private fun payloadLabel(type: String): String = when (type) { "uri" -> "Link / Website"; "google_review" -> "Google Review"; "social" -> "Social Media"; "wifi" -> "Wi-Fi"; "contact" -> "Contact Card"; "phone" -> "Phone"; "email" -> "Email"; "sms" -> "SMS"; "location" -> "Location"; "text" -> "Plain Text"; "custom" -> "Custom URI"; "multiple" -> "Multiple Records"; else -> type.replace('_', ' ').replaceFirstChar(Char::uppercase) }
private fun recordLabel(type: String): String = when (type.lowercase()) { "uri" -> "Link / Website"; "text" -> "Plain Text"; "text/vcard" -> "Contact Card"; "application/vnd.wfa.wsc" -> "Wi-Fi"; else -> type }
private fun payloadDescription(payload: NfcPayload): String = when (payload) { is NfcPayload.UriPayload -> payload.uri; is NfcPayload.Wifi -> payload.ssid; is NfcPayload.Contact -> payload.name; is NfcPayload.Text -> payload.text; is NfcPayload.Multiple -> "${payload.items.size} NDEF records" }

@Composable private fun Info(label: String, value: String) = ListItem({ Text(value) }, overlineContent = { Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))

@Composable private fun TagLibrary(tags: List<NfcTagEntity>, select: (NfcTagEntity) -> Unit) { var filter by remember { mutableStateOf("All") }; val filters = listOf("All", "Links", "Social", "WiFi", "Contacts", "Reviews", "Text"); val shown = tags.filter { filter == "All" || when(filter) { "Links" -> it.ndefType == "uri"; "Social" -> it.ndefType == "social"; "WiFi" -> it.ndefType == "wifi"; "Contacts" -> it.ndefType == "contact"; "Reviews" -> it.ndefType == "google_review"; "Text" -> it.ndefType == "text"; else -> true } }; LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { Text("My tags", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp) }
    item { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { filters.forEach { FilterChip(filter == it, { filter = it }, { Text(it) }, shape = RoundedCornerShape(9.dp)) } } }
    if (shown.isEmpty()) item { EmptyState(Icons.Default.Nfc, if (filter == "All") "No tags yet" else "No $filter tags", if (filter == "All") "Written tags will appear here." else "Try another filter.", Modifier.fillMaxWidth().height(390.dp)) }
    else items(shown, key = { it.id }) { tag -> Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { select(tag) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { ListItem({ Text((if (tag.isMock) "🧪 " else "") + (tag.name ?: "Unnamed tag"), fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("${tag.ndefType.replace('_',' ')} • ${tag.securityStatus.replace('_',' ')}${if (tag.isMock) " • MOCK" else ""}") }, leadingContent = { Icon(Icons.Default.Nfc, null, tint = if (tag.isMock) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
} }

@Composable private fun ProjectsScreen(projects: List<ProjectEntity>, vm: AppViewModel) { var show by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; Column { Button({ show = true }, Modifier.padding(16.dp).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(13.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New project") }; if (projects.isEmpty()) EmptyState(Icons.Default.Folder, "No projects", "Group related NFC tags into projects.") else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(projects, key = { it.id }) { p -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { ListItem({ Text(p.name, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("${p.category} • ${p.description.orEmpty()}") }, leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } } } }; if (show) AlertDialog({ show = false }, { Button({ vm.saveProject(name, desc, "other"); show = false }) { Text("Save") } }, title = { Text("New project") }, text = { Column { Field(name, { name = it }, "Name"); Spacer(Modifier.height(10.dp)); Field(desc, { desc = it }, "Description", false) } }, dismissButton = { TextButton({ show = false }) { Text("Cancel") } }) }

@Composable private fun HistoryScreen(history: List<HistoryEntity>) { var query by remember { mutableStateOf("") }; val shown = history.filter { query.isBlank() || listOf(it.operation, it.payloadType, it.oldContentType, it.newContentType, it.tagUid, it.errorMessage).any { value -> value?.contains(query, true) == true } }; LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("History", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp) }; item { OutlinedTextField(query, { query = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp)) }; if (shown.isEmpty()) item { EmptyState(Icons.Default.History, if (history.isEmpty()) "No history yet" else "No results", if (history.isEmpty()) "Your NFC activity will appear here." else "Try a different search.", Modifier.fillMaxWidth().height(390.dp)) } else items(shown, key = { it.id }) { h -> val typeSummary = if (h.operation == "rewrite") "${payloadLabel(h.oldContentType ?: "unknown")} → ${payloadLabel(h.newContentType ?: h.payloadType)}" else payloadLabel(h.payloadType); Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { ListItem({ Text((if (h.isMock) "🧪 " else "") + h.operation.lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("$typeSummary • ${h.tagUid ?: "UID unavailable"}${if (h.isMock) " • MOCK" else ""}\n${DateFormat.getDateTimeInstance().format(Date(h.createdAt))}") }, trailingContent = { Icon(if (h.verificationSuccess) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (h.verificationSuccess) Color(0xFF34C759) else MaterialTheme.colorScheme.error) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } } } }

@Composable private fun BatchScreen(engine: NfcEngine, state: NfcUiState, vm: AppViewModel, tags: List<NfcTagEntity>) {
    var payloadText by remember { mutableStateOf("") }; var selectedId by remember { mutableStateOf<String?>(null) }; var quantityText by remember { mutableStateOf("10") }; var protect by remember { mutableStateOf(false) }; var running by remember { mutableStateOf(false) }; var complete by remember { mutableIntStateOf(0) }; var failed by remember { mutableIntStateOf(0) }; var duplicates by remember { mutableIntStateOf(0) }; val seen = remember { mutableStateListOf<String>() }
    val selected = tags.firstOrNull { it.id == selectedId }?.let(vm::payloadFor)
    val payload = selected ?: NfcPayload.UriPayload(normalizeWebUrl(payloadText).orEmpty())
    LaunchedEffect(state.stage, state.snapshot?.uid) {
        if (!running || state.stage !in setOf(NfcStage.SUCCESS, NfcStage.ERROR)) return@LaunchedEffect
        if (state.stage == NfcStage.SUCCESS) {
            val uid = state.snapshot?.uid
            if (uid != null && uid in seen) duplicates++ else { uid?.let(seen::add); complete++ }
        } else failed++
        val target = quantityText.toIntOrNull() ?: 0
        if (complete + failed < target) {
            delay(650)
            if (engine.mockModeEnabled) engine.nextMockTag(complete + failed + 1)
            engine.begin(PendingNfcOperation.InspectThenWrite(payload, if (protect) ProtectionRequest.AutoGenerated else ProtectionRequest.None))
        } else running = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!running && complete == 0 && failed == 0) {
            val canStart = selected != null || normalizeWebUrl(payloadText) != null
            val quantity = quantityText.toIntOrNull() ?: 0
            FeatureHero(Icons.Default.DynamicFeed, "PRODUCTION MODE", "Program a tag series", "Reuse one verified configuration across multiple physical NFC tags with duplicate detection.", Color(0xFFAF52DE))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("1  Choose content", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Select a managed configuration, or enter a URL for this batch.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tags.isNotEmpty()) {
                        Text("MANAGED CONFIGURATIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = .8.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag -> FilterChip(selectedId == tag.id, { selectedId = if (selectedId == tag.id) null else tag.id }, { Text(tag.name ?: "Unnamed") }, leadingIcon = { Icon(Icons.Default.Nfc, null, Modifier.size(17.dp)) }) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                    }
                    if (selectedId == null) OutlinedTextField(payloadText, { payloadText = it }, Modifier.fillMaxWidth(), label = { Text("Batch URL") }, placeholder = { Text("https://example.com") }, leadingIcon = { Icon(Icons.Default.Link, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759)); Spacer(Modifier.width(10.dp)); Column { Text("Managed configuration selected", fontWeight = FontWeight.SemiBold); Text(tags.firstOrNull { it.id == selectedId }?.name ?: "Unnamed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("2  Set production options", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Choose the number of tags and whether each one receives a unique write credential.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(quantityText, { quantityText = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Number of tags") }, leadingIcon = { Icon(Icons.Default.Tag, null) }, singleLine = true, shape = RoundedCornerShape(14.dp), supportingText = { Text("Up to 10,000 tags per batch") })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)) { Icon(Icons.Default.Security, null, Modifier.padding(9.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Managed write protection", fontWeight = FontWeight.SemiBold); Text("Unique encrypted credential for every tag", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(protect, { protect = it }) }
                }
            }
            Button({ val q = quantityText.toIntOrNull() ?: 0; if (q > 0 && canStart) { vm.createBatch(payload, q, engine.mockModeEnabled); if (engine.mockModeEnabled) engine.nextMockTag(1); running = true; engine.begin(PendingNfcOperation.InspectThenWrite(payload, if (protect) ProtectionRequest.AutoGenerated else ProtectionRequest.None)) } }, Modifier.fillMaxWidth().height(56.dp), enabled = canStart && quantity in 1..10_000, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (quantity > 0) "START BATCH OF $quantity" else "START BATCH", fontWeight = FontWeight.Bold) }
        } else {
            val q = quantityText.toIntOrNull() ?: 1
            FeatureHero(if (running) Icons.Default.Nfc else Icons.Default.CheckCircle, if (running) "BATCH IN PROGRESS" else "COMPLETE", if (running) "Ready for tag ${complete + failed + 1}" else "$complete completed • $failed failed", if (running) "Keep this screen open and present each NFC tag once." else "The batch finished with $duplicates duplicate scan${if (duplicates == 1) "" else "s"}.", if (running) Color(0xFFAF52DE) else Color(0xFF34C759))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Progress", fontWeight = FontWeight.SemiBold); Text("${complete + failed} / $q", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }; LinearProgressIndicator({ (complete + failed).toFloat() / q }, Modifier.fillMaxWidth().height(8.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Completed: $complete", color = Color(0xFF34C759), fontWeight = FontWeight.SemiBold); Text("Failed: $failed", color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold) }; Text(if (running) "${state.stage}: ${state.message}" else if (protect) "Every successful tag was verified and mock-protected." else "Every successful tag was verified.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (!running) Button({ complete = 0; failed = 0; duplicates = 0; seen.clear() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("START ANOTHER BATCH") }
        }
    }
}

@Composable
private fun SecurityScreen(engine: NfcEngine, nfc: NfcUiState, vm: AppViewModel, tags: List<NfcTagEntity>) {
    val source = remember { nfc.snapshot }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var manualAuth by remember { mutableStateOf(false) }
    var manualCredential by remember { mutableStateOf("") }
    var showManualCredential by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(1) }
    var checked by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    val uid = source?.uid
    val managed = tags.firstOrNull { it.tagUid == uid }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Tag security", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        if (nfc.operation in setOf("protection_enabled", "protection_removed", "permanent_lock", "manual_authentication") && nfc.stage != NfcStage.IDLE) item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = when (nfc.stage) { NfcStage.SUCCESS -> Color(0xFF30D158).copy(alpha = .14f); NfcStage.ERROR -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.primaryContainer }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(nfc.stage.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold)
                    Text(nfc.message, fontSize = 13.sp)
                    if (nfc.stage == NfcStage.ERROR && nfc.operation == "manual_authentication") Button({ engine.cancel(); manualAuth = true }) { Text("ENTER ANOTHER CREDENTIAL") }
                    else if (nfc.stage == NfcStage.ERROR) Button(engine::retry) { Text(if (nfc.message.startsWith("DIFFERENT NFC TAG")) "SCAN ORIGINAL TAG" else "TRY AGAIN") }
                }
            }
        }
        item { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Column { Info("Physical chip", source?.securityVariant ?: "Unsupported or uncertain"); Info("Managed protection", source?.securityState?.replace('_', ' ') ?: "Scan a tag first"); Info("Public reading", if (source?.securityState == "protected") "Allowed — no password required" else "Unchanged"); if (source?.securityState == "protected") Info("Password mode", when (managed?.credentialMode) { "custom" -> "Custom"; "auto_generated" -> "Auto-generated"; else -> "Managed credential unavailable" }) } } }
        item {
            Text("Managed write protection", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Uses a unique device-encrypted credential. It blocks supported unauthorized writes while ordinary taps remain public.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            if (source?.securityState == "protected") Button({ confirmRemove = true }, Modifier.fillMaxWidth()) { Text("REMOVE WRITE PROTECTION") }
            else Button({ confirmEnable = true }, Modifier.fillMaxWidth(), enabled = uid != null && source?.securityVariant != null && source.writable == true) { Text("ENABLE MANAGED PROTECTION") }
            source?.securityReason?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
        item { HorizontalDivider(); TextButton({ advanced = !advanced }, Modifier.fillMaxWidth()) { Text(if (advanced) "HIDE ADVANCED" else "ADVANCED • PERMANENT READ-ONLY") } }
        if (advanced && com.nfcwriter.app.BuildConfig.DEBUG) item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DEVELOPER / TESTING", color = Color(0xFFFF9F0A), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Disable automatic credential retrieval"); Text("Temporary test mode; physical configuration is unchanged.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(!engine.automaticCredentialRetrievalEnabled, { engine.automaticCredentialRetrievalEnabled = !it }) }
                OutlinedButton({ manualAuth = true }, Modifier.fillMaxWidth(), enabled = uid != null && source?.securityState == "protected") { Text("MANUAL AUTHENTICATION") }
                OutlinedButton({ confirmForget = true }, Modifier.fillMaxWidth(), enabled = uid != null && managed?.credentialId != null) { Text("FORGET LOCAL CREDENTIAL") }
            } }
        }
        if (advanced) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("⚠ PERMANENT READ-ONLY", color = MaterialTheme.colorScheme.error, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                when (step) {
                    1 -> { Text("This permanently disables rewriting of the supported NDEF area and cannot be undone."); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { checked = it }); Text("I understand this cannot be undone.") }; Button({ step = 2 }, enabled = checked) { Text("CONTINUE") } }
                    2 -> { Text("Type LOCK PERMANENTLY"); Field(phrase, { phrase = it }, "Confirmation phrase"); Button({ step = 3 }, enabled = phrase == "LOCK PERMANENTLY" && uid != null) { Text("CONTINUE TO FINAL HOLD") } }
                    else -> Surface(Modifier.fillMaxWidth().height(64.dp).pointerInput(uid) { if (uid != null) awaitPointerEventScope { while (true) { awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue; val held = withTimeoutOrNull(3_000) { do { if (awaitPointerEvent().changes.none { it.pressed }) return@withTimeoutOrNull false } while (true) }; if (held == null) engine.begin(PendingNfcOperation.PermanentReadOnly(uid)) } } }, color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(12.dp)) { Box(contentAlignment = Alignment.Center) { Text("HOLD 3 SECONDS TO LOCK PERMANENTLY", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) } }
                }
            }
        }
    }
    if (confirmEnable) AlertDialog({ confirmEnable = false }, { Button({ confirmEnable = false; uid?.let { engine.begin(PendingNfcOperation.EnableProtection(it)) } }) { Text("ENABLE") } }, title = { Text("Enable write protection?") }, text = { Text("You will rescan the same tag. TapTag will save an encrypted credential before configuring write-only protection and will verify that public reading still works.") }, dismissButton = { TextButton({ confirmEnable = false }) { Text("Cancel") } })
    if (confirmRemove) AlertDialog({ confirmRemove = false }, { Button({ confirmRemove = false; uid?.let { engine.begin(PendingNfcOperation.RemoveProtection(it)) } }) { Text("REMOVE") } }, title = { Text("Remove write protection?") }, text = { Text("This requires the authorized credential and a rescan of the same physical tag. Normal rewrites never remove protection automatically.") }, dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } })
    if (manualAuth) AlertDialog({ manualAuth = false; manualCredential = "" }, { Button({ val credential = runCatching { NtagCredential.fromHex(manualCredential) }.getOrNull(); if (credential != null && uid != null) { manualAuth = false; manualCredential = ""; engine.begin(PendingNfcOperation.ManualAuthenticate(uid, credential)) } }, enabled = runCatching { NtagCredential.fromHex(manualCredential) }.isSuccess) { Text("AUTHENTICATE") } }, title = { Text("Enter NFC credential") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(manualCredential, { manualCredential = it.take(17) }, label = { Text("12 hex characters") }, visualTransformation = if (showManualCredential) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showManualCredential, { showManualCredential = it }); Text("Show credential") }; Text("This tests physical authentication only. It will not rewrite, erase, or change security settings.", fontSize = 12.sp) } }, dismissButton = { TextButton({ manualAuth = false; manualCredential = "" }) { Text("Cancel") } })
    if (confirmForget) AlertDialog({ confirmForget = false }, { Button({ confirmForget = false; uid?.let(vm::forgetLocalCredential) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("FORGET LOCAL CREDENTIAL") } }, title = { Text("Forget local credential?") }, text = { Text("Developer/testing only. This removes the encrypted credential cached on this device. Physical protection, cloud ciphertext, tag records, content, and history remain unchanged.") }, dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } })
}

@Composable private fun SettingsScreen(vm: AppViewModel, engine: NfcEngine, availability: NfcAvailability, pending: Int, themeMode: ThemeMode, setThemeMode: (ThemeMode) -> Unit, sync: () -> Unit, signOut: () -> Unit, openNfc: () -> Unit, openAdmin: () -> Unit) { var vibrate by remember { mutableStateOf(true) }; var sound by remember { mutableStateOf(true) }; var verify by remember { mutableStateOf(true) }; val mock by engine.mockConfiguration.collectAsState(); LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    item { Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp) }
    item { SettingsSection("ACCESS") { SettingsRow(Icons.Default.Key, accessRoleName(vm.accessRole()), vm.accessLabel()); if (vm.isAccessManager()) { SettingsDivider(); SettingsRow(Icons.Default.AdminPanelSettings, "Manage access keys", "Create, pause or revoke", openAdmin) }; SettingsDivider(); SettingsAction(Icons.Default.Logout, "Lock TapTag", signOut, destructive = true) } }
    item { Column { Text("APPEARANCE", Modifier.padding(start = 14.dp, bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { Row(Modifier.padding(5.dp)) { ThemeMode.entries.forEach { mode -> val selected = themeMode == mode; Surface(Modifier.weight(1f).clickable { setThemeMode(mode) }, shape = RoundedCornerShape(9.dp), color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, shadowElevation = if (selected) 1.dp else 0.dp) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase), Modifier.padding(vertical = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) } } } } } }
    item { SettingsSection("NFC") { SettingsRow(Icons.Default.Nfc, "NFC status", availability.name.lowercase().replaceFirstChar(Char::uppercase), if (availability == NfcAvailability.DISABLED) openNfc else null); SettingsDivider(); SwitchRow("Vibration", vibrate, Icons.Default.Vibration) { vibrate = it }; SettingsDivider(); SwitchRow("Success sound", sound, Icons.Default.VolumeUp) { sound = it }; SettingsDivider(); SwitchRow("Automatic verification", verify, Icons.Default.Verified) { verify = it } } }
    item { SettingsSection("SYNC") { SettingsRow(Icons.Default.CloudDone, "Supabase", if (pending == 0) "Connected • Up to date" else "Connected • $pending to sync", sync) } }
    if (BuildConfig.DEBUG) item { DeveloperMockOptions(engine, mock) }
    item { SettingsSection("ABOUT") { SettingsRow(Icons.Default.Info, "TapTag", "Version 1.0.0") } }
} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeveloperMockOptions(engine: NfcEngine, configuration: MockNfcConfiguration) {
    var stateExpanded by remember { mutableStateOf(false) }
    var faultExpanded by remember { mutableStateOf(false) }
    var cloudFaultExpanded by remember { mutableStateOf(false) }
    var unauthorizedResult by remember { mutableStateOf<String?>(null) }
    var failureAt by remember(configuration.batchVerificationFailureAt) { mutableStateOf(configuration.batchVerificationFailureAt.takeIf { it > 0 }?.toString().orEmpty()) }
    Column {
        Text("DEVELOPER OPTIONS", Modifier.padding(start = 14.dp, bottom = 7.dp), color = Color(0xFFFF9F0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFFFF9F0A).copy(alpha = .15f)) { Text("🧪", Modifier.padding(9.dp), fontSize = 20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("Mock NFC Mode", fontWeight = FontWeight.SemiBold); Text("Application simulator — never physical NFC", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(configuration.enabled, engine::setMockMode)
                }
                if (configuration.enabled) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFF9F0A).copy(alpha = .10f)) { Text("No Android NFC commands are sent while this mode is active. Results prove only TapTag application behavior.", Modifier.padding(12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ExposedDropdownMenuBox(stateExpanded, { stateExpanded = it }) {
                        OutlinedTextField(configuration.state.label, {}, Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), readOnly = true, label = { Text("Simulated tag state") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(stateExpanded) })
                        ExposedDropdownMenu(stateExpanded, { stateExpanded = false }) { MockTagState.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, { engine.setMockTagState(value); stateExpanded = false }, leadingIcon = { if (value == configuration.state) Icon(Icons.Default.Check, null) }) } }
                    }
                    ExposedDropdownMenuBox(faultExpanded, { faultExpanded = it }) {
                        OutlinedTextField(configuration.fault.label, {}, Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), readOnly = true, label = { Text("Force next NFC result") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(faultExpanded) })
                        ExposedDropdownMenu(faultExpanded, { faultExpanded = false }) { MockNfcFault.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, { engine.setMockFault(value); faultExpanded = false }, leadingIcon = { if (value == configuration.fault) Icon(Icons.Default.Check, null) }) } }
                    }
                    ExposedDropdownMenuBox(cloudFaultExpanded, { cloudFaultExpanded = it }) {
                        OutlinedTextField(configuration.cloudFault.label, {}, Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), readOnly = true, label = { Text("Force mock sync result") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cloudFaultExpanded) })
                        ExposedDropdownMenu(cloudFaultExpanded, { cloudFaultExpanded = false }) { MockCloudFault.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, { engine.setMockCloudFault(value); cloudFaultExpanded = false }, leadingIcon = { if (value == configuration.cloudFault) Icon(Icons.Default.Check, null) }) } }
                    }
                    Text(if (BuildConfig.MOCK_SYNC_ENABLED) "Mock uploads are enabled. Use only a development Supabase project." else "Mock records stay on this device and are excluded from cloud sync.", fontSize = 12.sp, color = Color(0xFFFF9F0A))
                    OutlinedTextField(failureAt, { text -> failureAt = text.filter(Char::isDigit).take(3); engine.setMockBatchFailureAt(failureAt.toIntOrNull() ?: 0) }, Modifier.fillMaxWidth(), label = { Text("Batch verification failure at tag #") }, supportingText = { Text("Use 0 or leave blank for no forced batch failure.") }, singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ engine.resetMockTag() }, Modifier.weight(1f)) { Text("RESET") }
                        OutlinedButton({ engine.nextMockTag() }, Modifier.weight(1f)) { Text("NEXT TAG") }
                    }
                    Button({ unauthorizedResult = engine.simulateUnauthorizedWrite() }, Modifier.fillMaxWidth()) { Text("SIMULATE UNAUTHORIZED WRITE") }
                    unauthorizedResult?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (it.startsWith("✓")) Color(0xFF34C759) else MaterialTheme.colorScheme.error) }
                    if (configuration.state == MockTagState.WRITE_PROTECTED) Text("Test-only credential: ${MockNfcOperations.TEST_CREDENTIAL}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF9F0A))
                    Text("Mock UID: ${configuration.uid}\nNTAG215 • NfcA / NDEF • ${MockNfcOperations.NTAG215_CAPACITY} bytes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun AdminDashboard(vm: AppViewModel) {
    if (!vm.isAccessManager()) {
        EmptyState(Icons.Default.Lock, "Administrator access required", "An admin or super-admin key is required to manage access keys.")
        return
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var label by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("user") }
    var generatedKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<List<ManagedAccessKey>>(emptyList()) }
    var loadingKeys by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingAction by remember { mutableStateOf<Pair<ManagedAccessKey, String>?>(null) }
    var editingKey by remember { mutableStateOf<ManagedAccessKey?>(null) }
    LaunchedEffect(refresh) {
        loadingKeys = true
        runCatching { vm.listAccessKeys() }
            .onSuccess { keys = it }
            .onFailure { message = it.message }
        loadingKeys = false
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Access-key dashboard", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
            Spacer(Modifier.height(6.dp))
            Text("Create and control access for TapTag operators. New key values are shown only once.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Create access key", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(label, { label = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name or purpose") }, singleLine = true)
                    Text("Access level", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AccessRoleSelector(if (vm.isSuperAdmin()) listOf("user", "admin", "superadmin") else listOf("user"), selectedRole) { selectedRole = it }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                runCatching { vm.createAccessKey(label, selectedRole) }
                                    .onSuccess { generatedKey = it.accessKey; label = ""; refresh++ }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !busy && label.isNotBlank(),
                        shape = RoundedCornerShape(13.dp),
                    ) { Text(if (busy) "Creating…" else "Create access key") }
                }
            }
        }
        generatedKey?.let { key ->
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("New access key", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Copy this key now. TapTag cannot show it again.", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                        SelectionContainer { Text(key, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
                        OutlinedButton({ clipboard.setText(AnnotatedString(key)); message = "Access key copied." }, Modifier.fillMaxWidth()) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy key") }
                    }
                }
            }
        }
        message?.let { value -> item { Text(value, color = if (value.endsWith("copied.")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Manage keys", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                IconButton({ refresh++ }, enabled = !loadingKeys) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        if (loadingKeys) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (keys.isEmpty()) {
            item { Text("No access keys found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(keys, key = { it.keyId }) { key ->
                AccessKeyManagementCard(
                    key = key,
                    canEdit = key.canManage || (key.isCurrent && vm.isSuperAdmin()),
                ) { action ->
                    if (action == "edit") editingKey = key else pendingAction = key to action
                }
            }
        }
    }
    pendingAction?.let { (key, action) ->
        val destructive = action in setOf("revoke", "delete")
        val actionTitle = if (action == "rotate_copy") "Rotate and copy" else action.replaceFirstChar(Char::uppercase)
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            title = { Text("$actionTitle ${key.label}?") },
            text = { Text(when (action) {
                "pause" -> "This key will stop working until it is resumed."
                "resume" -> "This key will be allowed to access TapTag again."
                "revoke" -> "This permanently revokes the key. It cannot be resumed."
                "rotate_copy" -> "For security, the existing value cannot be recovered. It will be replaced with a new key, copied to your clipboard, and shown once."
                else -> "This permanently deletes the key record and all sessions using it."
            }) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                if (action == "rotate_copy") vm.rotateAccessKey(key.keyId).accessKey.also {
                                    generatedKey = it
                                    clipboard.setText(AnnotatedString(it))
                                }
                                else { vm.manageAccessKey(key.keyId, action); null }
                            }
                                .onSuccess { message = if (action == "rotate_copy") "New key for ${key.label} was copied to the clipboard." else "${key.label} was ${if (action == "delete") "deleted" else action + "d"}."; pendingAction = null; refresh++ }
                                .onFailure { message = it.message; pendingAction = null }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text(if (busy) "Working…" else if (action == "rotate_copy") "ROTATE & COPY" else action.uppercase()) }
            },
            dismissButton = { TextButton({ pendingAction = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
    editingKey?.let { key ->
        var editLabel by remember(key.keyId) { mutableStateOf(key.label) }
        var editRole by remember(key.keyId) { mutableStateOf(key.role) }
        AlertDialog(
            onDismissRequest = { if (!busy) editingKey = null },
            title = { Text("Edit access key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(editLabel, { editLabel = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name or purpose") }, singleLine = true)
                    Text("Access level", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AccessRoleSelector(if (vm.isSuperAdmin()) listOf("user", "admin", "superadmin") else listOf("user"), editRole) { editRole = it }
                    if (key.isCurrent) Text("As Super Admin, you may edit or rotate and copy your current key. Pausing, revoking, and deleting it remain blocked.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { vm.editAccessKey(key.keyId, editLabel, editRole) }
                                .onSuccess { message = "${it.label} was updated."; editingKey = null; refresh++ }
                                .onFailure { message = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && editLabel.isNotBlank(),
                ) { Text(if (busy) "Saving…" else "Save") }
            },
            dismissButton = { TextButton({ editingKey = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

@Composable private fun AccessKeyManagementCard(key: ManagedAccessKey, canEdit: Boolean, action: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(key.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("${key.keyPrefix}  •  ${accessRoleName(key.role)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Surface(shape = RoundedCornerShape(50), color = when (key.status) { "active" -> Color(0xFF30D158).copy(alpha = .16f); "paused" -> Color(0xFFFF9F0A).copy(alpha = .18f); else -> MaterialTheme.colorScheme.errorContainer }) {
                    Text(key.status.uppercase(), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Created ${key.createdAt.take(10)}${key.lastUsedAt?.let { "  •  Last used ${it.take(10)}" } ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (key.isCurrent) {
                Text("Current session key", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (canEdit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ action("edit") }, Modifier.weight(1f)) { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit", maxLines = 1) }
                    if (key.status != "revoked") OutlinedButton({ action("rotate_copy") }, Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Rotate & copy", maxLines = 1) }
                }
            }
            if (!key.isCurrent && key.canManage) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (key.status == "active") OutlinedButton({ action("pause") }, Modifier.weight(1f)) { Text("Pause", maxLines = 1) }
                    if (key.status == "paused") OutlinedButton({ action("resume") }, Modifier.weight(1f)) { Text("Resume", maxLines = 1) }
                    if (key.status != "revoked") OutlinedButton({ action("revoke") }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Revoke", maxLines = 1) }
                }
                OutlinedButton({ action("delete") }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete permanently", maxLines = 1) }
            }
        }
    }
}

@Composable private fun AccessRoleSelector(roles: List<String>, selectedRole: String, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        roles.forEach { role ->
            val selected = role == selectedRole
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { select(role) },
                shape = RoundedCornerShape(13.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .65f) else MaterialTheme.colorScheme.outline.copy(alpha = .35f)),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { select(role) })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(accessRoleName(role), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                        Text(accessRoleDescription(role), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

private fun accessRoleName(role: String): String = when (role) {
    "superadmin" -> "Super Admin"
    "admin" -> "Admin"
    else -> "Normal Access"
}

private fun accessRoleDescription(role: String): String = when (role) {
    "superadmin" -> "Full access, including admins and other super admins."
    "admin" -> "Can create and manage normal access keys."
    else -> "Can use TapTag without access-management controls."
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) = Column { Text(title, Modifier.padding(start = 14.dp, bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { Column(content = content) } }
@Composable private fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
@Composable private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, action: (() -> Unit)? = null) = Row(Modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(onClick = action) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Text(title, Modifier.weight(1f), fontSize = 16.sp); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); if (action != null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SettingsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit, destructive: Boolean = false) = Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) { val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary; Icon(icon, null, Modifier.size(22.dp), tint = color); Spacer(Modifier.width(14.dp)); Text(label, color = color, fontSize = 16.sp) }
@Composable private fun SwitchRow(label: String, checked: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Text(label, Modifier.weight(1f), fontSize = 16.sp); Switch(checked, change) }

@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, modifier: Modifier = Modifier.fillMaxSize()) = Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Icon(icon, null, Modifier.padding(20.dp).size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }; Spacer(Modifier.height(16.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) } }

private fun startOfToday(): Long = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

@Composable private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> = collectAsState()
private fun AppViewModel.viewModelScopeCompat(block: suspend () -> Unit) { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { block() } }
