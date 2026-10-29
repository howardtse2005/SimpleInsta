package app.simpleinsta.ig.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A button you have to mean.
 *
 * Tapping does nothing. Holding for [holdMillis] fills the line and fires once.
 * The point is not decoration — it is that leaving this app should cost a
 * deliberate second, so a reflex tap cannot undo the whole thing.
 */
@Composable
fun HoldToOpen(
    label: String,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1600,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    trackColor: Color = MaterialTheme.colorScheme.outline,
    onComplete: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = holdMillis,
                                    easing = LinearEasing
                                )
                            )
                            onComplete()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        progress.snapTo(0f)
                    }
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(trackColor, RoundedCornerShape(1.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(contentColor, RoundedCornerShape(1.dp))
            )
        }
    }
}
