package com.diegonmarcos.superapp.wallet

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.filament.Engine
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.texture.ImageTexture

/**
 * A card rendered as an actual 3D object — Filament geometry, a real camera and
 * a real light, not a Compose layer pretending.
 *
 * This is the `3d-f` path docs/3d-view-design.md always meant: native
 * Vulkan/OpenGL, one engine, and no WebView. The card is a [CubeNode] cut to a
 * physical ID-1 card's proportions (85.6 × 54 × 0.76 mm), textured with a
 * bitmap painted from the card's own data, lit from off-axis so the face
 * catches light as it turns.
 *
 * The face texture is drawn with android.graphics rather than captured from the
 * Compose card: a texture has to be a bitmap either way, and painting it
 * directly is one Canvas instead of an offscreen layer capture per card.
 *
 * KNOWN LIMIT — why this is not the default anywhere. SceneView renders into a
 * SurfaceView, and a surface punches a hole the compositor fills directly, so
 * it does not obey the clip of a scrolling parent: a card in a LazyColumn draws
 * straight over the tab strip as the list moves. Every card container in this
 * app scrolls, so this stays behind the IDs tab's 3D-f toggle until the
 * renderer can target a TextureView instead.
 *
 * ponytail: a box, not a .glb. A credit card IS a box, and shipping a mesh for
 * one would mean an asset pipeline for a shape with six faces. Load a model the
 * day a passport needs to open.
 */
@Composable
internal fun FilamentCard(
    card: WalletStore.Card,
    modifier: Modifier = Modifier,
    /** Hoist this to the tab and every card in the list shares one Filament
     *  engine. A Scene defaults to creating its own, and a list of them would
     *  mean an engine per row — which is the mistake the WebView path makes
     *  with GL contexts, in a different costume. */
    engine: Engine = rememberEngine(),
) {
    val materialLoader = rememberMaterialLoader(engine)
    // Back off far enough that the whole card fits the frustum at full yaw.
    val cameraNode = rememberCameraNode(engine) { position = Position(z = 2.6f) }

    val bitmap = remember(card.id, card.brand, card.number) { paintCardFace(card) }

    val cube = remember(bitmap) {
        val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
        CubeNode(
            engine = engine,
            // ID-1 proportions. Thin, but not zero — the edge is the whole
            // point of rendering this as geometry.
            size = Size(1.71f, 1.08f, 0.03f),
            center = Position(0.0f),
            materialInstance = materialLoader.createTextureInstance(
                texture = texture,
                metallic = 0.25f,
                roughness = 0.35f,
            ),
        )
    }

    // Set once, never animated. A wallet is a stack of cards lying still;
    // a deck that turns on its own is a screensaver, not a wallet.
    LaunchedEffect(cube) {
        cube.rotation = Rotation(x = 6f, y = -16f, z = 0f)
    }

    DisposableEffect(cube) { onDispose { cube.destroy() } }

    Scene(
        modifier = modifier,
        engine = engine,
        materialLoader = materialLoader,
        cameraNode = cameraNode,
        childNodes = remember(cube) { listOf(cube) },
        // Transparent background so the card floats on the app's own ground
        // instead of sitting in a black rectangle.
        isOpaque = false,
    )
}

/**
 * The card's face, as a texture.
 *
 * Deliberately the card's own data and nothing invented: brand, tagline and
 * whatever identifier it carries. The 2D decks stay the place where a card is
 * read in detail; this is the object you turn over.
 */
private fun paintCardFace(card: WalletStore.Card): Bitmap {
    val w = 856
    val h = 540
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)

    val base = Color(card.accent.toULong().toLong())
    val lit = Color(
        red = (base.red + 0.18f).coerceAtMost(1f),
        green = (base.green + 0.18f).coerceAtMost(1f),
        blue = (base.blue + 0.18f).coerceAtMost(1f),
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f, 0f, w.toFloat(), h.toFloat(),
        lit.toArgb(), base.toArgb(), Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null

    // Chip — the one piece of furniture every card in this wallet has.
    paint.color = 0xFFD9C179.toInt()
    canvas.drawRoundRect(RectF(64f, 168f, 188f, 262f), 14f, 14f, paint)
    paint.color = 0x33000000
    canvas.drawRoundRect(RectF(64f, 168f, 188f, 262f), 14f, 14f, paint)

    paint.color = android.graphics.Color.WHITE
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = 46f
    canvas.drawText(card.brand.take(28), 64f, 104f, paint)

    paint.typeface = Typeface.DEFAULT
    paint.textSize = 28f
    paint.color = 0xCCFFFFFF.toInt()
    canvas.drawText(card.tagline.take(46), 64f, 148f, paint)

    val id = card.number.ifBlank { card.eventLocation }
    if (id.isNotBlank()) {
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 44f
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(id.take(24), 64f, 396f, paint)
    }

    paint.typeface = Typeface.DEFAULT
    paint.textSize = 24f
    paint.color = 0x99FFFFFF.toInt()
    canvas.drawText(card.kind.uppercase(), 64f, 470f, paint)

    return bmp
}
