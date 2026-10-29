package app.simpleinsta.ig.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import app.simpleinsta.ig.AppViewModel
import app.simpleinsta.ig.SimpleInstaApp
import app.simpleinsta.ig.core.Reel
import app.simpleinsta.ig.core.StopReason
import app.simpleinsta.ig.web.InstagramApp
import app.simpleinsta.ig.web.UrlPolicy
import app.simpleinsta.ig.web.WebHost
import kotlinx.coroutines.delay
import org.json.JSONObject

enum class Tab(val label: String) {
    FEED("Feed"),
    MESSAGES("Messages"),
    QUEUE("Queue")
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as SimpleInstaApp
    val vm: AppViewModel = viewModel()

    var tab by remember { mutableStateOf(Tab.FEED) }
    var path by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var blockedLabel by remember { mutableStateOf<String?>(null) }
    var webKey by remember { mutableStateOf(0) }
    var debugOpen by remember { mutableStateOf(false) }

    // null = not checked yet. false means the injected policy never ran, which
    // turns the app into a plain browser — worth saying out loud.
    var policyOk by remember { mutableStateOf<Boolean?>(null) }

    var reelFitFill by remember { mutableStateOf(app.prefs.reelFitFill) }

    // Instagram can stay installed. The door to it is deliberate (see HoldToOpen);
    // automatic handoffs stay refused either way.
    var allowHandoff by remember { mutableStateOf(app.prefs.allowAppHandoff) }
    val instagramInstalled = remember { InstagramApp.isInstalled(context) }

    val queue by vm.queue.collectAsState()
    val pending by vm.pending.collectAsState()
    val loggedOut by vm.loggedOut.collectAsState()
    val logLines by vm.logLines.collectAsState()

    val onEvent: (JSONObject) -> Unit = { json ->
        when (json.optString("type")) {
            "reels" -> vm.onReelsExtracted(parseReels(json))
            "caughtUp" -> {
                val reason = StopReason.from(json.optString("reason"))
                // The watermark still advances; the chip that used to
                // announce it is gone by request.
                vm.onFeedCaughtUp(reason, json.optLong("oldest"))
            }
            "health" -> vm.onHealth(
                feedSeen = json.optBoolean("feedSeen"),
                payloads = json.optInt("payloads"),
                loggedOut = json.optBoolean("loggedOut"),
                path = json.optString("path")
            )
            "blocked" -> blockedLabel = json.optString("label").ifBlank { "That page" }

            // A reel link was tapped inside Instagram. Never let the pager open;
            // jump the native queue to that reel instead.
            "openReel" -> {
                // An empty shortcode is valid: the shield sends one when it
                // cannot name the reel. It just starts at the top of the queue.
                vm.openReel(json.optString("shortcode"))
                tab = Tab.QUEUE
            }

            "feed" -> vm.onFeedStats(
                kept = json.optInt("kept"),
                removed = json.optInt("removed"),
                pages = json.optInt("pages"),
                payloads = json.optInt("payloads"),
                caughtUp = json.optBoolean("caughtUp")
            )

            "followingTab" -> vm.onFollowingTab(json.optBoolean("found"))

            "log" -> vm.onLog(json.optString("name"), json.optString("detail"))

            // Mirror Instagram's own theme instead of guessing from the system.
            "theme" -> app.darkTheme.value = json.optBoolean("dark")

            else -> Unit
        }
    }

    // Instagram's create flow opens a file picker. A WebView with no
    // onShowFileChooser handler silently does nothing when the page asks for a
    // file, which is exactly what "I cannot post" looks like.
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@rememberLauncherForActivityResult

        val uri = result.data?.data
        callback.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK && uri != null) arrayOf(uri) else null
        )
    }

    val host = remember(webKey) { FrameLayout(context) }
    val webView = remember(webKey) {
        WebHost.create(
            context = context,
            app = app,
            hooks = WebHost.Hooks(
                onEvent = { json -> onEvent(json) },
                onPath = { visited ->
                    path = visited
                    // Instagram's nav does the navigating now, so the tab is
                    // derived from the page rather than from a button.
                    if (tab != Tab.QUEUE) {
                        tab = if (visited.startsWith("/direct")) Tab.MESSAGES else Tab.FEED
                    }
                },
                onLoading = { loading = it },
                onBlocked = { blockedLabel = it },
                onPolicyCheck = { policyOk = it },
                onFileChooser = { callback, params ->
                    pendingFileCallback = callback
                    val intent = runCatching { params.createIntent() }.getOrNull()
                    if (intent == null) {
                        pendingFileCallback = null
                        callback.onReceiveValue(null)
                    } else {
                        runCatching { filePicker.launch(intent) }.onFailure {
                            pendingFileCallback = null
                            callback.onReceiveValue(null)
                        }
                    }
                },
                onRendererGone = { webKey += 1 }
            )
        )
    }

    DisposableEffect(webKey) {
        host.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        onDispose {
            host.removeView(webView)
            webView.destroy()
        }
    }

    // One load, ever. After this the page navigates itself: the bottom bar is
    // Instagram's, not ours, so there is no tab state to keep in sync.
    LaunchedEffect(webKey) {
        if (webView.url == null) webView.loadUrl(UrlPolicy.HOME)
    }

    LaunchedEffect(blockedLabel) {
        if (blockedLabel != null) {
            // The app-handoff chip carries a hold-to-open control, so it needs
            // long enough to actually hold.
            delay(if (blockedLabel == UrlPolicy.LABEL_APP) 8_000 else 2_500)
            blockedLabel = null
        }
    }

    BackHandler {
        val currentPath = UrlPolicy.pathOf(webView.url)
        val atHome = currentPath.isEmpty() || currentPath == "/"
        val inDirect = currentPath == "/direct" || currentPath.startsWith("/direct/")
        val atInbox = currentPath == "/direct/inbox/" || currentPath == "/direct" ||
            currentPath == "/direct/"

        when {
            tab == Tab.QUEUE -> {
                vm.endSession()
                tab = Tab.FEED
            }

            // A fixed ladder, not history. `goBack()` is silently dropped while a
            // page is busy, which is why back sometimes did nothing at all. And
            // there is no reel branch any more: a stale "reel is open" flag could
            // swallow every press, which is exactly what "back stopped working"
            // was. Thread -> inbox -> feed -> exit.
            inDirect && !atInbox -> webView.loadUrl(UrlPolicy.HOME + "direct/inbox/")
            inDirect -> webView.loadUrl(UrlPolicy.HOME)

            atHome -> (context as? Activity)?.finish()

            webView.canGoBack() -> webView.goBack()

            else -> webView.loadUrl(UrlPolicy.HOME)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(onLongPress = { debugOpen = true })

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { host },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.visibility = if (tab == Tab.QUEUE) View.GONE else View.VISIBLE
                }
            )

            if (loading && tab != Tab.QUEUE) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
            }

            if (loggedOut && tab != Tab.QUEUE) {
                SignInChip(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                )
            }

            if (tab == Tab.QUEUE) {
                QueuePane(
                    state = queue,
                    onNext = { vm.next() },
                    onPrev = { vm.prev() },
                    onClose = {
                        vm.endSession()
                        tab = Tab.FEED
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            blockedLabel?.let { blocked ->
                BlockedChip(
                    label = blocked,
                    canOpenApp = blocked == UrlPolicy.LABEL_APP && allowHandoff && instagramInstalled,
                    onOpenApp = {
                        InstagramApp.open(context)
                        blockedLabel = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }

        // No second nav bar: Instagram's own is right there, inside the page.
        // The queue is the one surface that bar cannot reach, so it gets a small
        // floating entry that appears only when it has something in it.
        if (pending > 0 && tab != Tab.QUEUE) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.beginSession()
                            tab = Tab.QUEUE
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (pending == 1) "1 reel waiting" else "$pending reels waiting",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Watch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    if (debugOpen) {
        DebugDialog(
            status = vm.statusLine() + "\npolicy: " + when (policyOk) {
                true -> "attached"
                false -> "NOT ATTACHED"
                null -> "not checked"
            },
            logs = logLines,
            allowHandoff = allowHandoff,
            instagramInstalled = instagramInstalled,
            onToggleHandoff = {
                allowHandoff = !allowHandoff
                app.prefs.allowAppHandoff = allowHandoff
            },
            onDismiss = { debugOpen = false },
            onClearQueue = { vm.markAllCaughtUp() },
            onResetLimits = { vm.resetLimits() },
            reelFitFill = reelFitFill,
            onToggleReelFit = {
                reelFitFill = !reelFitFill
                app.prefs.reelFitFill = reelFitFill
                // The policy is injected at document start, so a reload is
                // the honest way to apply a new config.
                webView.reload()
            }
        )
    }
}

@Composable
private fun TopBar(onLongPress: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SimpleInsta",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(5.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun SignInChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = "Sign in to Instagram to use SimpleInsta.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BlockedChip(
    label: String,
    canOpenApp: Boolean,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1A1A18)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "$label isn't available in SimpleInsta.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF3F2EE)
            )
            if (canOpenApp) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Holding opens it — and its feed. SimpleInsta can't follow you in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9A9791)
                )
                Spacer(Modifier.height(10.dp))
                HoldToOpen(
                    label = "Hold to open Instagram",
                    containerColor = Color(0xFF2A2A27),
                    contentColor = Color(0xFFF3F2EE),
                    trackColor = Color(0xFF3D3D39),
                    onComplete = onOpenApp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DebugDialog(
    status: String,
    logs: List<String>,
    allowHandoff: Boolean,
    instagramInstalled: Boolean,
    onToggleHandoff: () -> Unit,
    onDismiss: () -> Unit,
    onClearQueue: () -> Unit,
    onResetLimits: () -> Unit,
    reelFitFill: Boolean,
    onToggleReelFit: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)

                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "What the page saw",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        logs.takeLast(30).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (instagramInstalled) {
                        "Instagram is installed. Automatic handoffs are still refused; " +
                            "the hold-to-open control appears when you tap a link into the app."
                    } else {
                        "Instagram is not installed on this device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onToggleHandoff, enabled = instagramInstalled) {
                    Text(
                        if (allowHandoff) "Disable the hold-to-open door"
                        else "Enable the hold-to-open door"
                    )
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onToggleReelFit) {
                    Text(
                        if (reelFitFill) "Reels: fit screen (edges cropped)"
                        else "Reels: whole frame (bars)"
                    )
                }
                TextButton(onClick = { onResetLimits(); onDismiss() }) {
                    Text("Reset feed limits")
                }
                TextButton(onClick = { onClearQueue(); onDismiss() }) {
                    Text("Mark every reel as watched")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private fun parseReels(json: JSONObject): List<Reel> {
    val array = json.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<Reel>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val videoUrl = item.optString("videoUrl")
        if (videoUrl.isBlank()) continue
        out.add(
            Reel(
                mediaId = item.optString("mediaId"),
                shortcode = item.optString("shortcode"),
                author = item.optString("author"),
                sender = item.optString("sender"),
                sentAt = item.optLong("sentAt"),
                videoUrl = videoUrl,
                thumbUrl = item.optString("thumbUrl"),
                durationMs = item.optInt("durationMs")
            )
        )
    }
    return out
}
