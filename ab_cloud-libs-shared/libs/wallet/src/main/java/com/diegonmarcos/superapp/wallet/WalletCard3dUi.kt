package com.diegonmarcos.superapp.wallet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Turns a flat Compose card into an object with volume.
 *
 * Four things together, and it is the combination that reads as 3D — any one
 * of them alone still looks like a picture of a card:
 *
 *  1. **Thickness.** The card's edge is extruded behind its face, so you see
 *     the slab it is cut from. This is the part that was missing: perspective
 *     on a zero-thickness plane is still a plane.
 *  2. **Perspective.** A near camera, so rotation foreshortens instead of
 *     shearing. Without [cameraDistance] a rotationY is an affine squash.
 *  3. **Motion at rest.** A slow, per-card sway. A card you have not touched
 *     has to move to prove it has depth; a static tilt just looks like a
 *     skewed rectangle.
 *  4. **A moving highlight.** The specular band slides against the rotation,
 *     which is what tells the eye the surface is catching light.
 *
 * The tilt follows your finger, hard. Pointer events are observed on the
 * Initial pass and never consumed, so taps still land and lists still scroll —
 * the card reacts to a gesture it does not own.
 *
 * ponytail: extrusion + perspective, not a mesh. The WebGL path in
 * [IdCard3DReactView] stays an opt-in experiment — this repo's own
 * docs/3d-view-design.md calls a WebView renderer unfit for production
 * Android, and one JS engine per row in a scrolling list is why.
 */
@Composable
internal fun Modifier.card3d(
    shape: Shape = RoundedCornerShape(20.dp),
    /** Degrees at the very edge of the card under your finger. */
    maxTilt: Float = 20f,
    /** Lie-back at rest — the card sits on a surface, facing slightly up. */
    restTilt: Float = 7f,
    /** Card stock, in dp. A real credit card is ~0.76mm; this is theatre, not
     *  metrology, and under 6dp the edge stops being visible on a phone. */
    thickness: Float = 9f,
    /** Colour of the extruded edge. Dark by default because every deck here
     *  sits on the same near-black violet ground. */
    edge: Color = Color(0xFF150E24),
    elevation: Float = 18f,
): Modifier {
    val cardShape = shape
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableFloatStateOf(0f) }

    val spec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
    }
    val rx = animateFloatAsState(restTilt + tiltX, spec, label = "card3d-rx")
    val ry = animateFloatAsState(tiltY, spec, label = "card3d-ry")
    val lift = animateFloatAsState(pressed, spec, label = "card3d-lift")

    // Idle sway. Phased per card so a list breathes instead of pulsing in
    // lockstep, and slow enough (7s) to read as weight rather than jitter.
    val phase = remember { Random.nextInt(0, 7000) }
    val sway by rememberInfiniteTransition(label = "card3d-idle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(7000), RepeatMode.Reverse, initialStartOffset = StartOffset(phase),
        ),
        label = "card3d-sway",
    )

    /** Live rotation: the finger wins, the sway fills the silence. */
    fun liveY(): Float = ry.value + (sway - 0.5f) * 9f * (1f - lift.value)
    fun liveX(): Float = rx.value + (sway - 0.5f) * 4f * (1f - lift.value)

    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fun aim(at: Offset) {
                    // −1..1 from the card's centre: the corner you press dips.
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
            // Unitless, same scale as the 8f default — smaller is a stronger
            // perspective. Compose applies density itself.
            cameraDistance = 6f
            rotationX = liveX()
            rotationY = liveY()
            translationY = -lift.value * 5f * density
            shadowElevation = (elevation + lift.value * 12f) * density
            this.shape = cardShape
            // NOT clipped: the extrusion below is drawn outside the face's
            // bounds, and a clipping layer would shave the card's edge off —
            // which is exactly how it stayed flat before.
            clip = false
            spotShadowColor = Color(0xFF120826)
            ambientShadowColor = Color(0xFF120826)
        }
        .drawBehind {
            // The slab. Copies of the face outline stepped away from the
            // viewer, so the card has a side you can see.
            val outline = cardShape.createOutline(size, layoutDirection, this)
            // Direction the stock recedes in: down-right at rest, swinging
            // with the tilt so the visible edge changes as the card turns.
            val step = thickness.dp.toPx() / STEPS
            val dx = (0.90f + liveY() / maxTilt * 0.85f) * step
            val dy = (0.45f - (liveX() - restTilt) / maxTilt * 0.50f) * step
            for (i in STEPS downTo 1) {
                translate(dx * i, dy * i) {
                    drawOutline(outline, color = edge)
                }
            }
        }
        // The face itself is clipped; the slab behind it is not.
        .clip(cardShape)
        .drawWithContent {
            drawContent()
            val travel = (liveY() / maxTilt).coerceIn(-1f, 1f)
            val cx = size.width * (0.5f - travel * 0.6f)
            val cy = size.height * (0.5f - (liveX() - restTilt) / maxTilt * 0.35f)
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.18f + 0.10f * lift.value),
                    1.0f to Color.Transparent,
                    start = Offset(cx - size.width * 0.55f, cy - size.height * 0.9f),
                    end = Offset(cx + size.width * 0.55f, cy + size.height * 0.9f),
                ),
            )
        }
}

/** Extrusion slices. Ten is where the edge stops showing banding on a phone
 *  and more only costs draw calls on every card in a list. */
private const val STEPS = 10
