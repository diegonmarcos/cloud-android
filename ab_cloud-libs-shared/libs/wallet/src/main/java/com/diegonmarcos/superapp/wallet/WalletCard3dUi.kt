package com.diegonmarcos.superapp.wallet

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Gives a flat Compose card real depth: perspective, a resting lie-back, a
 * shadow that belongs to the tilt, and a specular sheen that slides across the
 * face as the card turns.
 *
 * Why this and not the WebGL path in [IdCard3DReactView]: the wallet's own
 * design note calls a WebView renderer "not recommended for production native
 * Android" — a JS engine per card drains battery and fights the native list it
 * sits in. That path stays where it is, an opt-in experiment for one ID card.
 * This one is a Modifier, so every deck in the app — Pay, IDs, Vcards, tickets,
 * bookings — becomes three-dimensional at one call site each, with no new
 * dependency and nothing to load.
 *
 * The tilt follows your finger. Pointer events are observed on the Initial
 * pass and never consumed, so taps still land and the list still scrolls;
 * the card is reacting to a gesture it does not own.
 *
 * ponytail: perspective + sheen + shadow, not a mesh. Reach for Filament the
 * day a card needs to be turned over and read from behind.
 */
@Composable
internal fun Modifier.card3d(
    shape: Shape = RoundedCornerShape(20.dp),
    /** Degrees at the very edge of the card. Past ~14 the text on a credit
     *  card stops being readable, which is the point at which "3D" becomes
     *  "broken". */
    maxTilt: Float = 11f,
    /** Lie-back at rest, so a card that is not being touched still reads as an
     *  object on a surface rather than a rectangle painted on one. */
    restTilt: Float = 4f,
    elevation: Float = 18f,
): Modifier {
    val cardShape = shape
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableFloatStateOf(0f) }

    val spec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    }
    val rx = animateFloatAsState(restTilt + tiltX, spec, label = "card3d-rx")
    val ry = animateFloatAsState(tiltY, spec, label = "card3d-ry")
    val lift = animateFloatAsState(pressed, spec, label = "card3d-lift")

    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fun aim(at: Offset) {
                    // −1..1 from the card's centre, so the corner you press is
                    // the corner that dips.
                    val nx = (at.x / size.width.coerceAtLeast(1) - 0.5f) * 2f
                    val ny = (at.y / size.height.coerceAtLeast(1) - 0.5f) * 2f
                    tiltX = -ny * maxTilt
                    tiltY = nx * maxTilt
                }
                aim(down.position)
                pressed = 1f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    aim(change.position)
                }
                tiltX = 0f
                tiltY = 0f
                pressed = 0f
            }
        }
        .graphicsLayer {
            // Without a camera distance the rotation is an affine squash, not
            // perspective — the single line that separates 3D from a shear.
            // Unitless, same scale as the 8f default — smaller is a stronger
            // perspective. Not multiplied by density: Compose already does.
            cameraDistance = 12f
            rotationX = rx.value
            rotationY = ry.value
            translationY = -lift.value * 4f * density
            shadowElevation = (elevation + lift.value * 10f) * density
            this.shape = cardShape
            clip = true
            // Warm the shadow towards the app's violet ground so a lifted card
            // does not stamp a grey rectangle onto it.
            spotShadowColor = Color(0xFF1A0B33)
            ambientShadowColor = Color(0xFF1A0B33)
        }
        .drawWithContent {
            drawContent()
            // Specular sweep: a soft band that travels with the tilt, which is
            // what actually reads as a surface catching light.
            val travel = (ry.value / maxTilt).coerceIn(-1f, 1f)
            val cx = size.width * (0.5f - travel * 0.6f)
            val cy = size.height * (0.5f - (rx.value - restTilt) / maxTilt * 0.35f)
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.16f + 0.10f * lift.value),
                    1.0f to Color.Transparent,
                    start = Offset(cx - size.width * 0.55f, cy - size.height * 0.9f),
                    end = Offset(cx + size.width * 0.55f, cy + size.height * 0.9f),
                ),
            )
        }
}
