@file:OptIn(ExperimentalMaterial3Api::class)

package com.alexdiaz.mailtriage

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.selection.SelectionContainer
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App()
            }
        }
    }
}

private enum class Screen { INBOX, ACCOUNTS, SETTINGS, RULES }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.INBOX) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reader = state.reader

    BackHandler(enabled = reader != null) { vm.closeReader() }
    BackHandler(enabled = reader == null && screen != Screen.INBOX) {
        screen = if (screen == Screen.RULES) Screen.SETTINGS else Screen.INBOX
    }

    val onArchive: (TriagedEmail) -> Unit = { e ->
        vm.archiveEmail(e)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = "Archived", actionLabel = "Undo", duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) vm.undoDelete(e.key)
        }
    }

    val onDelete: (TriagedEmail) -> Unit = { e ->
        vm.deleteEmail(e)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = "Moved to Trash", actionLabel = "Undo", duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) vm.undoDelete(e.key)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = ev.message, actionLabel = "Undo", duration = SnackbarDuration.Long
            )
            if (r == SnackbarResult.ActionPerformed) vm.undoAuto(ev.keys)
        }
    }

    val onMove: (TriagedEmail, String) -> Unit = { e, folder ->
        vm.moveEmail(e, folder)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = "Moved to ${folderLabel(folder)}", actionLabel = "Undo", duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) vm.undoDelete(e.key)
        }
    }
    var showMove by remember { mutableStateOf(false) }
    if (reader == null) showMove = false
    if (showMove && reader != null) {
        MoveDialog(
            reader = reader,
            onPick = { folder -> showMove = false; onMove(reader.email, folder) },
            onRetry = vm::loadFolders,
            onDismiss = { showMove = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (reader != null) Text("") else Text(
                        when (screen) {
                            Screen.INBOX -> "Mail Triage"
                            Screen.ACCOUNTS -> "Accounts"
                            Screen.SETTINGS -> "Settings"
                            Screen.RULES -> "Learned rules"
                        }
                    )
                },
                navigationIcon = {
                    if (reader != null) TextButton(onClick = vm::closeReader) { Text("‹ Back") }
                    else if (screen != Screen.INBOX) TextButton(onClick = { screen = Screen.INBOX }) { Text("‹ Back") }
                },
                actions = {
                    if (reader != null) {
                        TextButton(onClick = { vm.loadFolders(); showMove = true }) { Text("Move") }
                        TextButton(onClick = { onArchive(reader.email) }) { Text("Archive") }
                        TextButton(onClick = { onDelete(reader.email) }) { Text("Delete") }
                    } else if (screen == Screen.INBOX) {
                        TextButton(onClick = { screen = Screen.ACCOUNTS }) { Text("Accounts") }
                        TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Settings") }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (reader != null) ReaderScreen(reader, vm, accountColor(state.accounts, reader.email.key))
            else when (screen) {
                Screen.INBOX -> InboxScreen(state, vm, onDelete, onArchive)
                Screen.ACCOUNTS -> AccountsScreen(state, vm)
                Screen.SETTINGS -> SettingsScreen(state, vm, onOpenRules = { screen = Screen.RULES })
                Screen.RULES -> RulesScreen(state, vm)
            }
        }
    }
}

// ------------------------------------------------------------------ Inbox

@Composable
private fun InboxScreen(
    state: UiState, vm: MainViewModel,
    onDelete: (TriagedEmail) -> Unit, onArchive: (TriagedEmail) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var unreadOnly by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf<String?>(null) }     // null = all accounts
    if (accountId != null && state.accounts.none { it.id == accountId }) accountId = null
    val cats = Category.entries
    val inAccount = if (accountId == null) state.emails
        else state.emails.filter { it.key.startsWith("$accountId:") }
    val visible = if (unreadOnly) inAccount.filter { it.unread } else inAccount
    val shown = visible.filter { it.category == cats[tab] }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.lastRefresh == 0L) "Never checked"
                    else "Checked " + DateUtils.getRelativeTimeSpanString(state.lastRefresh),
                    style = MaterialTheme.typography.labelMedium
                )
                if (state.status.isNotBlank()) {
                    Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
            Button(onClick = vm::refresh, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Refresh")
            }
        }

        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = !unreadOnly, onClick = { unreadOnly = false },
                label = { Text("All (${inAccount.size})") })
            FilterChip(selected = unreadOnly, onClick = { unreadOnly = true },
                label = { Text("Unread (${inAccount.count { it.unread }})") })
        }

        if (state.accounts.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = accountId == null, onClick = { accountId = null },
                    label = { Text("All accounts") })
                state.accounts.forEach { a ->
                    val n = state.emails.count { it.key.startsWith("${a.id}:") }
                    FilterChip(
                        selected = accountId == a.id,
                        onClick = { accountId = if (accountId == a.id) null else a.id },
                        leadingIcon = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(a.color)))
                        },
                        label = { Text("${a.email.substringBefore('@')} ($n)") }
                    )
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            cats.forEachIndexed { i, c ->
                val n = visible.count { it.category == c }
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text("${c.name.lowercase().replaceFirstChar { it.uppercase() }} ($n)")
                })
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.accounts.isEmpty()) "Add an account to get started"
                    else if (unreadOnly) "No unread emails here"
                    else "Nothing here",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { it.key }) { email -> SwipeActions(email, onDelete, onArchive) { EmailCard(email, vm, accountColor(state.accounts, email.key)) } }
            }
        }
    }
}

/** Color of the account an email belongs to (gray if the account was removed). */
private fun accountColor(accounts: List<MailAccount>, key: String): Color =
    accounts.firstOrNull { key.startsWith("${it.id}:") }?.let { Color(it.color) } ?: Color(0xFF9E9E9E)

private fun priorityColor(p: Int) = when (p) {
    1 -> Color(0xFFD32F2F)
    2 -> Color(0xFFF57C00)
    3 -> Color(0xFFFBC02D)
    4 -> Color(0xFF64B5F6)
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun EmailCard(e: TriagedEmail, vm: MainViewModel, acctColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
      Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(acctColor))
        Column(Modifier.weight(1f).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(priorityColor(e.priority)))
                Spacer(Modifier.width(8.dp))
                Text(
                    e.from.substringBefore(" <"),
                    fontWeight = if (e.unread) FontWeight.ExtraBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(e.dateMillis).toString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                e.subject, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (e.unread) FontWeight.Bold else FontWeight.Normal, maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                e.summary, style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.accountEmail, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = acctColor, fontWeight = FontWeight.SemiBold
                )
                if (!e.aiSummarized) Text(
                    " · preview", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (expanded) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { vm.openEmail(e) }) { Text("Open") }
                    Category.entries.filter { it != e.category }.forEach { c ->
                        AssistChip(
                            onClick = { vm.recategorize(e, c) },
                            label = { Text("Move to ${c.name.lowercase()}") }
                        )
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun SwipeActions(
    e: TriagedEmail,
    onDelete: (TriagedEmail) -> Unit,
    onArchive: (TriagedEmail) -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onDelete(e); true }    // swipe right
                SwipeToDismissBoxValue.EndToStart -> { onArchive(e); true }   // swipe left
                else -> false
            }
        },
        positionalThreshold = { total -> total * 0.4f }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val toRight = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toLeft = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium)
                    .background(
                        when {
                            toRight -> Color(0xFFD32F2F)
                            toLeft -> Color(0xFF2E7D32)
                            else -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                if (toRight) Text("🗑  Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                if (toLeft) Text("Archive  📥", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    ) { content() }
}

@Composable
private fun MoveDialog(
    reader: ReaderState,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            when {
                reader.foldersError != null -> Column {
                    Text(reader.foldersError!!)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                reader.folders == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                reader.folders!!.isEmpty() -> Text("This account has no other folders.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(reader.folders!!) { f ->
                        Text(
                            folderLabel(f),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ Reader

@Composable
private fun ReaderScreen(r: ReaderState, vm: MainViewModel, acctColor: Color) {
    var showImages by remember(r.email.key) { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(r.full?.subject ?: r.email.subject, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(acctColor))
                Spacer(Modifier.width(6.dp))
                Text(r.email.accountEmail, style = MaterialTheme.typography.labelMedium,
                    color = acctColor, fontWeight = FontWeight.SemiBold)
            }
            Text("From: " + (r.full?.from ?: r.email.from), style = MaterialTheme.typography.bodySmall)
            r.full?.to?.takeIf { it.isNotBlank() }?.let {
                Text("To: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                DateUtils.formatDateTime(
                    ctx, r.email.dateMillis,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
                ),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
            )
            if (r.email.summary.isNotBlank() && r.email.aiSummarized) {
                Spacer(Modifier.height(6.dp))
                Text("Summary: " + r.email.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            if (r.full?.html != null && !showImages) {
                TextButton(onClick = { showImages = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("Show images")
                }
            }
        }
        HorizontalDivider()
        when {
            r.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(r.error!!, style = MaterialTheme.typography.bodyMedium)
            }
            r.full?.html != null -> EmailWebView(r.full!!.html!!, showImages)
            else -> SelectionContainer {
                Text(
                    r.full?.text.orEmpty().ifBlank { "(This email has no text content)" },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Renders the email's HTML. Scripts are off, links open in your browser, remote images only on request. */
@Composable
private fun EmailWebView(html: String, showImages: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
            }
        },
        update = { web ->
            val key = "${html.hashCode()}:$showImages"
            if (web.tag != key) {
                web.tag = key
                web.settings.blockNetworkImage = !showImages
                web.settings.loadsImagesAutomatically = true
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    )
}

// ------------------------------------------------------------------ Accounts

@Composable
private fun AccountsScreen(state: UiState, vm: MainViewModel) {
    var provider by remember { mutableStateOf(Provider.GMAIL) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.finishOutlookSignIn(email, res.data)
        email = ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Connected", style = MaterialTheme.typography.titleMedium)
        if (state.accounts.isEmpty()) Text("No accounts yet.")
        state.accounts.forEach { a ->
            var picking by remember(a.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
              Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(6.dp).fillMaxHeight().background(Color(a.color)))
                Column(Modifier.weight(1f).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(a.color))
                                .clickable { picking = !picking }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.email, fontWeight = FontWeight.SemiBold)
                            Text(a.provider.label + " · tap the circle to change color",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { vm.removeAccount(a) }) { Text("Remove") }
                    }
                    if (picking) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AccountColors.palette.forEach { c ->
                                val selected = c == a.color
                                Box(
                                    Modifier.size(if (selected) 34.dp else 28.dp).clip(CircleShape)
                                        .background(Color(c))
                                        .clickable { vm.setAccountColor(a.id, c); picking = false },
                                    contentAlignment = Alignment.Center
                                ) { if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
              }
            }
        }

        HorizontalDivider()
        Text("Add account", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.label) })
            }
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email address") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        if (provider.oauth) {
            Text(
                "Microsoft requires signing in through its own page. " +
                    "Make sure your Azure client ID is saved in Settings.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { vm.signInIntentFor(email)?.let { launcher.launch(it) } },
                enabled = email.contains("@")
            ) { Text("Sign in with Microsoft") }
        } else {
            if (provider == Provider.OTHER) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text("IMAP server (e.g. imap.example.com)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("App password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
            Text(appPasswordHelp(provider), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    vm.addPasswordAccount(provider, email, password, host)
                    email = ""; password = ""; host = ""
                },
                enabled = email.contains("@") && password.isNotBlank() &&
                    (provider != Provider.OTHER || host.isNotBlank())
            ) { Text("Add account") }
        }
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun appPasswordHelp(p: Provider) = when (p) {
    Provider.GMAIL -> "Turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords."
    Provider.AOL -> "AOL: Account Security → Generate app password."
    Provider.YAHOO -> "Yahoo: Account Security → Generate app password."
    else -> "Use your provider's app password if it offers one."
}

// ------------------------------------------------------------------ Learned rules

@Composable
private fun RulesScreen(state: UiState, vm: MainViewModel) {
    val need = state.settings.learnAfter
    if (state.rules.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nothing learned yet. Delete, move or re-sort a few emails and the app will start " +
                    "doing the same for similar ones from that sender.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.rules, key = { it.key }) { r ->
            val active = r.enabled && r.count >= need
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.describe(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                !r.enabled -> "Paused"
                                active -> "Active"
                                else -> "Learning ${r.count}/$need"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                        )
                    }
                    Text("From: ${r.sender}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Subject like: ${r.subjectPattern.ifBlank { "(empty)" }}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Done by you ${r.count}× · by the app ${r.applied}×",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                    Row {
                        TextButton(onClick = { vm.toggleRule(r.key) }) { Text(if (r.enabled) "Pause" else "Resume") }
                        TextButton(onClick = { vm.removeRule(r.key) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsScreen(state: UiState, vm: MainViewModel, onOpenRules: () -> Unit) {
    var key by remember(state.settings) { mutableStateOf(state.settings.claudeApiKey) }
    var model by remember(state.settings) { mutableStateOf(state.settings.claudeModel) }
    var clientId by remember(state.settings) { mutableStateOf(state.settings.azureClientId) }
    var max by remember(state.settings) { mutableStateOf(state.settings.maxPerAccount.toString()) }
    var autoLearn by remember(state.settings) { mutableStateOf(state.settings.autoLearn) }
    var learnAfter by remember(state.settings) { mutableStateOf(state.settings.learnAfter.toString()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = key, onValueChange = { key = it }, label = { Text("Claude API key") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )
        Text(
            "From console.anthropic.com → API Keys. Without a key, the app sorts with built-in rules and shows previews instead of summaries.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it }, label = { Text("Claude model") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = clientId, onValueChange = { clientId = it }, label = { Text("Azure client ID (for Hotmail/Outlook)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = max, onValueChange = { max = it.filter(Char::isDigit) },
            label = { Text("Newest emails to load per account") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text(
            "When you delete or move emails, older ones fill the list on the next refresh. " +
                "Claude summarizes each email only once.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = {
            vm.saveSettings(
                AppSettings(
                    claudeApiKey = key,
                    claudeModel = model.ifBlank { "claude-haiku-5-5" },
                    azureClientId = clientId,
                    maxPerAccount = max.toIntOrNull()?.coerceIn(10, 1000) ?: 200,
                    autoLearn = autoLearn,
                    learnAfter = learnAfter.toIntOrNull()?.coerceIn(1, 10) ?: 2
                )
            )
        }) { Text("Save") }

        HorizontalDivider()
        Text("Learn from what I do", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "When I delete, move or re-sort an email, do the same for the next one from that sender with a similar subject.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Switch(checked = autoLearn, onCheckedChange = { autoLearn = it })
        }
        OutlinedTextField(
            value = learnAfter, onValueChange = { learnAfter = it.filter(Char::isDigit) },
            label = { Text("Start after doing it this many times") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text("Tap Save after changing these.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenRules) { Text("Learned rules (${state.rules.size})") }
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
    }
}
