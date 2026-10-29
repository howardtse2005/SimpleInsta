package app.simpleinsta.ig.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.simpleinsta.ig.core.QueueState
import app.simpleinsta.ig.core.Reel

/**
 * The queue.
 *
 * This is the whole product in one screen: one reel at a time, no vertical
 * swipe, and a Next button. Continuing is a decision rather than a reflex, which
 * is the only difference that matters between this and Instagram's pager.
 */
@Composable
fun QueuePane(
    state: QueueState,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current = state.current

    if (current == null) {
        CaughtUpPane(
            title = if (state.done) "You're all caught up." else "Nothing new from your friends.",
            subtitle = if (state.done) {
                "That was every reel anyone sent you."
            } else {
                "Reels people send you will show up here."
            },
            onClose = onClose,
            modifier = modifier
        )
        return
    }

    Column(modifier.background(MaterialTheme.colorScheme.background)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.position,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ReelPlayer(reel = current, onSkip = onNext, modifier = Modifier.fillMaxSize())
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPrev, enabled = state.index > 0) {
                Text("Prev")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onNext,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (state.index >= state.items.lastIndex) "Done" else "Next")
            }
        }
    }
}

@Composable
private fun ReelPlayer(reel: Reel, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var failed by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(reel.mediaId) {
        failed = false
        paused = false
        runCatching {
            player.setMediaItem(MediaItem.fromUri(reel.videoUrl))
            player.prepare()
            player.playWhenReady = true
        }.onFailure { failed = true }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize()
        )

        // Tap anywhere to pause. No scrubber, no engagement bar.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(reel.mediaId) {
                    detectTapGestures {
                        if (player.isPlaying) {
                            player.pause()
                            paused = true
                        } else {
                            player.play()
                            paused = false
                        }
                    }
                }
        )

        if (paused && !failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(Color(0x66000000), RoundedCornerShape(28.dp))
                )
            }
        }

        if (failed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101010)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Couldn't load this reel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE8E6E1)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "The link may have expired.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A9791)
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = Color(0xFFE8E6E1))
                    }
                }
            }
        }
    }
}

@Composable
fun CaughtUpPane(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.width(140.dp)
            ) {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
