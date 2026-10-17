package one.graystack.plugins.nativesign.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativephp.mobile.ui.nativerender.NativeUIBridge
import com.nativephp.mobile.ui.nativerender.NativeUINode
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Signature pad: the user signs with a finger, and after every stroke the whole drawing goes
 * to PHP as a PNG data URL through `on_change` (an empty string once cleared).
 *
 * The drawing stays here, in composition state; PHP only ever receives it. While the pad has no
 * strokes of its own it shows the `value` PHP holds, so a signature survives the screen being
 * rebuilt. When the pad is turned or resized, what was drawn becomes a backdrop, shown at its own
 * size (scaled down only where it no longer fits). Strokes drawn on a shown signature add to it;
 * only the clear button starts over.
 *
 * The exported PNG is cropped to what was drawn, transparent, at most 1200 px wide, and drawn in
 * `export_color` (black by default) whatever the stroke looks like on screen, so a signature given
 * in dark mode still prints.
 *
 * With `allow_rotation`, the activity may follow the sensor (never upside down) while such a pad
 * is shown, even in an app that is otherwise portrait only; the orientation it had is restored
 * once the last of them goes.
 */
object SignaturePadRenderer {
    /** The widest exported PNG; wider drawings are scaled down. */
    private const val MAX_EXPORT_WIDTH = 1200f

    /** The most pixels a shown `value` may decode to. */
    private const val MAX_VALUE_PIXELS = 4_000_000

    /** Pads on screen that allow turning — counted, as a new screen appears before the old one goes. */
    private var rotatingPads = 0

    /** The activity's orientation before the first such pad appeared. */
    private var orientationBefore: Int? = null

    @Composable
    fun Render(node: NativeUINode, modifier: Modifier) {
        val p = node.props
        val strokeColor = Color(p.getColor("stroke_color", 0xFF1C1C1E.toInt()))
        val lineColor = Color(p.getColor("line_color", 0x668E8E93))
        val exportColor = p.getColor("export_color", 0xFF000000.toInt())
        val strokeWidthDp = p.getFloat("stroke_width", 3f)
        val placeholder = p.getString("placeholder")
        val clearLabel = p.getString("clear_label", "Clear")
        val accentColor = if (p.has("accent_color")) Color(p.getColor("accent_color")) else null
        val disabled = p.getBool("disabled")
        val allowsRotation = p.getBool("allow_rotation")
        val onChange = p.getCallbackId("on_change")
        val a11yLabel = p.getString("a11y_label")
        val a11yHint = p.getString("a11y_hint")
        val value = p.getString("value")

        val density = LocalDensity.current
        val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
        val activity = LocalContext.current.findActivity()

        val strokes = remember(node.id) { mutableStateListOf<List<Offset>>() }
        var currentStroke by remember(node.id) { mutableStateOf<List<Offset>>(emptyList()) }
        var padSize by remember(node.id) { mutableStateOf(IntSize.Zero) }
        var dismissedValue by remember(node.id) { mutableStateOf(false) }
        // What was drawn before the pad was turned or rebuilt, kept under the new strokes
        var backdrop by remember(node.id) { mutableStateOf<Bitmap?>(null) }

        // Decoded only while it is actually shown, not each time PHP echoes the latest stroke back
        val showsValue = !dismissedValue && backdrop == null && strokes.isEmpty() && currentStroke.isEmpty()
        val valueBitmap = remember(value, showsValue) { if (showsValue) decode(value) else null }
        val shownBackdrop = backdrop ?: valueBitmap
        val hasDrawing = strokes.isNotEmpty() || currentStroke.isNotEmpty() || shownBackdrop != null

        // The drawing handler outlives recompositions: it reads the latest of these
        val currentValueBitmap by rememberUpdatedState(valueBitmap)
        val currentExportColor by rememberUpdatedState(exportColor)
        val currentStrokeWidthPx by rememberUpdatedState(strokeWidthPx)
        val currentOnChange by rememberUpdatedState(onChange)

        // Follow the sensor while such a pad is shown; put the orientation back when the last goes
        DisposableEffect(activity, allowsRotation) {
            if (allowsRotation && activity != null) {
                if (rotatingPads++ == 0) {
                    orientationBefore = activity.requestedOrientation
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
                }
            }
            onDispose {
                if (allowsRotation && activity != null) {
                    rotatingPads = max(0, rotatingPads - 1)
                    if (rotatingPads == 0) {
                        orientationBefore?.let { activity.requestedOrientation = it }
                        orientationBefore = null
                    }
                }
            }
        }

        fun send(text: String) {
            val callbackId = currentOnChange
            if (callbackId != 0) {
                NativeUIBridge.sendTextChangeEvent(callbackId, node.id, text)
            }
        }

        fun clamp(point: Offset): Offset = Offset(
            point.x.coerceIn(0f, padSize.width.toFloat()),
            point.y.coerceIn(0f, padSize.height.toFloat()),
        )

        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .onSizeChanged { newSize ->
                    // Turned or resized: new strokes no longer fit, so they join the backdrop, which
                    // is shown at its own size — a drawing without new strokes is left as it is, so
                    // going back and forth never shrinks it
                    if (strokes.isNotEmpty() && padSize != IntSize.Zero && newSize != padSize) {
                        backdrop = render(backdrop, strokes.toList(), padSize, currentExportColor, currentStrokeWidthPx)
                        strokes.clear()
                        currentStroke = emptyList()
                    }
                    padSize = newSize
                }
                .semantics {
                    contentDescription = listOf(a11yLabel.ifEmpty { placeholder }, a11yHint).filter { it.isNotEmpty() }.joinToString(", ")
                }
                .pointerInput(node.id, disabled) {
                    if (disabled) return@pointerInput

                    awaitEachGesture {
                        // Consuming every change keeps a surrounding scroll view from scrolling
                        val down = awaitFirstDown()
                        down.consume()

                        if (strokes.isEmpty() && !dismissedValue) {
                            // Strokes add to the signature shown from the value
                            backdrop = backdrop ?: currentValueBitmap
                            dismissedValue = true
                        }

                        val points = mutableListOf(clamp(down.position))
                        currentStroke = points.toList()

                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    break
                                }
                                change.consume()
                                points.add(clamp(change.position))
                                currentStroke = points.toList()
                            }
                        } catch (cancelled: CancellationException) {
                            // An interrupted gesture leaves no half stroke behind
                            currentStroke = emptyList()
                            throw cancelled
                        }

                        if (points.size == 1) {
                            // A tap leaves a dot
                            points.add(points[0] + Offset(0.5f, 0.5f))
                        }

                        strokes.add(points.toList())
                        currentStroke = emptyList()
                        send(export(backdrop, strokes.toList(), padSize, currentExportColor, currentStrokeWidthPx))
                    }
                },
        ) {
            // The signature line
            Canvas(Modifier.matchParentSize()) {
                val inset = 20.dp.toPx()
                val y = size.height * 0.75f
                drawLine(lineColor, Offset(inset, y), Offset((size.width - inset).coerceAtLeast(inset), y), strokeWidth = 1.dp.toPx())
            }

            if (!hasDrawing && placeholder.isNotEmpty()) {
                Text(placeholder, color = lineColor, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
            }

            if (shownBackdrop != null && padSize != IntSize.Zero) {
                val frame = backdropRect(shownBackdrop, padSize)
                Image(
                    bitmap = shownBackdrop.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                        .size(with(density) { frame.width().toDp() }, with(density) { frame.height().toDp() }),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(strokeColor),
                )
            }

            Canvas(Modifier.matchParentSize()) {
                (strokes + listOf(currentStroke)).filter { it.isNotEmpty() }.forEach { stroke ->
                    drawPath(
                        path = composePath(stroke),
                        color = strokeColor,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }

            if (hasDrawing && !disabled) {
                TextButton(
                    onClick = {
                        strokes.clear()
                        currentStroke = emptyList()
                        backdrop = null
                        dismissedValue = true
                        send("")
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                    colors = if (accentColor != null) ButtonDefaults.textButtonColors(contentColor = accentColor) else ButtonDefaults.textButtonColors(),
                ) {
                    Text(clearLabel)
                }
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }

    /** Where a backdrop sits in the pad: centred, at its own size, scaled down only to fit. */
    private fun backdropRect(bitmap: Bitmap, size: IntSize): RectF {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return RectF()
        }

        val scale = min(1f, min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height))
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2

        return RectF(left, top, left + width, top + height)
    }

    /** A smooth line through the points: quadratic curves between their midpoints. */
    @Suppress("DEPRECATION")
    private fun composePath(points: List<Offset>): Path = Path().apply {
        moveTo(points[0].x, points[0].y)
        if (points.size < 3) {
            points.drop(1).forEach { lineTo(it.x, it.y) }
            return@apply
        }
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            quadraticBezierTo(previous.x, previous.y, (previous.x + current.x) / 2, (previous.y + current.y) / 2)
        }
        lineTo(points.last().x, points.last().y)
    }

    private fun androidPath(points: List<Offset>): android.graphics.Path = android.graphics.Path().apply {
        moveTo(points[0].x, points[0].y)
        if (points.size < 3) {
            points.drop(1).forEach { lineTo(it.x, it.y) }
            return@apply
        }
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            quadTo(previous.x, previous.y, (previous.x + current.x) / 2, (previous.y + current.y) / 2)
        }
        lineTo(points.last().x, points.last().y)
    }

    /**
     * The backdrop with the strokes on top, cropped to what was drawn, as a transparent bitmap —
     * scaled down to at most [MAX_EXPORT_WIDTH] wide.
     */
    private fun render(backdrop: Bitmap?, strokes: List<List<Offset>>, size: IntSize, color: Int, strokeWidthPx: Float): Bitmap? {
        if (size.width <= 0 || size.height <= 0) {
            return null
        }

        val backdropFrame = backdrop?.let { backdropRect(it, size) }
        val bounds = backdropFrame?.let { RectF(it) }
        var drawn: RectF? = bounds
        for (point in strokes.flatten()) {
            drawn = drawn?.apply { union(point.x, point.y) } ?: RectF(point.x, point.y, point.x, point.y)
        }
        val area = drawn ?: return null

        val padding = strokeWidthPx + 4f
        area.set(
            max(0f, area.left - padding),
            max(0f, area.top - padding),
            min(size.width.toFloat(), area.right + padding),
            min(size.height.toFloat(), area.bottom + padding),
        )
        if (area.width() < 1f || area.height() < 1f) {
            return null
        }

        val scale = if (area.width() > MAX_EXPORT_WIDTH) MAX_EXPORT_WIDTH / area.width() else 1f
        val bitmap = Bitmap.createBitmap(
            (area.width() * scale).roundToInt().coerceAtLeast(1),
            (area.height() * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-area.left, -area.top)

        if (backdrop != null && backdropFrame != null) {
            canvas.drawBitmap(backdrop, null, backdropFrame, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { canvas.drawPath(androidPath(it), paint) }

        return bitmap
    }

    /** The drawing as a transparent PNG data URL. */
    private fun export(backdrop: Bitmap?, strokes: List<List<Offset>>, size: IntSize, color: Int, strokeWidthPx: Float): String {
        val bitmap = render(backdrop, strokes, size, color, strokeWidthPx) ?: return ""

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()

        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(dataUrl: String): Bitmap? {
        if (!dataUrl.startsWith("data:image/png;base64,")) {
            return null
        }

        return try {
            val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)

            // A tiny file can still decode to an enormous canvas: look before decoding
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_VALUE_PIXELS) {
                return null
            }

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
