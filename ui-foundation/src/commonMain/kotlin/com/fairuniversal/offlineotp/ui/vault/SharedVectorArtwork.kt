package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableLineCap
import com.fairuniversal.offlineotp.ui.portable.PortableLineJoin
import com.fairuniversal.offlineotp.ui.portable.PortablePathOp
import com.fairuniversal.offlineotp.ui.portable.PortableThumbnailAssets
import com.fairuniversal.offlineotp.ui.portable.PortableVectorBrush
import com.fairuniversal.offlineotp.ui.portable.PortableVectorDocument
import com.fairuniversal.offlineotp.ui.portable.PortableVectorNode
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import kotlin.math.min

/**
 * Shared cached vector-drawable [VaultArtwork] backed by [PortableThumbnailAssets] and
 * [PortableVectorDocument]. Native hosts (such as iOS) supply only a read-only bundle XML lookup
 * `(drawableFile: String) -> String?`; parsing, caching and Canvas rendering are shared in
 * `:ui-contract` and `:ui-foundation`.
 */
class BundledVectorVaultArtwork(
    private val xmlLoader: (drawableFile: String) -> String?
) : VaultArtwork {
    private val cache = HashMap<String, PortableVectorDocument?>()

    override fun load(thumbnailName: String): ImageBitmap? = null

    override fun loadVector(thumbnailName: String): PortableVectorDocument? {
        val file = PortableThumbnailAssets.drawableFile(thumbnailName) ?: return null
        if (cache.containsKey(file)) return cache[file]
        val parsed = try {
            xmlLoader(file)?.let { PortableVectorDocument.parse(it) }
        } catch (_: Exception) {
            null
        }
        if (cache.size >= MAX_CACHED_VECTORS) {
            cache.clear()
        }
        cache[file] = parsed
        return parsed
    }

    companion object {
        private const val MAX_CACHED_VECTORS: Int = 512
    }
}

/** Renders a parsed [PortableVectorDocument] inside a high-contrast [ForestPalette.thumbnailBackground] badge. */
@Composable
fun SharedVectorBadge(
    document: PortableVectorDocument,
    sizeDp: Float,
    modifier: Modifier = Modifier
) {
    val clamped = sizeDp.coerceAtLeast(0f)
    val pad = (clamped * 0.1f).coerceIn(2f, 5f)
    val corner = (clamped * 0.22f).coerceIn(4f, 10f)
    Box(
        modifier = modifier
            .size(clamped.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(ForestPalette.thumbnailBackground)
            .padding(pad.dp),
        contentAlignment = Alignment.Center
    ) {
        val inner = (clamped - pad * 2f).coerceAtLeast(0f)
        SharedVectorCanvas(document = document, sizeDp = inner)
    }
}

/** Aspect-fit Compose [Canvas] renderer for [PortableVectorDocument]. */
@Composable
fun SharedVectorCanvas(
    document: PortableVectorDocument,
    sizeDp: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(sizeDp.coerceAtLeast(0f).dp)) {
        val vw = document.viewportWidth
        val vh = document.viewportHeight
        if (vw <= 0f || vh <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val scale = min(size.width / vw, size.height / vh)
        val dx = (size.width - vw * scale) / 2f
        val dy = (size.height - vh * scale) / 2f
        withTransform({
            translate(left = dx, top = dy)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
        }) {
            drawNodes(document.nodes)
        }
    }
}

private fun DrawScope.drawNodes(nodes: List<PortableVectorNode>) {
    for (node in nodes) {
        when (node) {
            is PortableVectorNode.Group -> {
                val clip = buildCombinedPath(node.clipPaths)
                withTransform({
                    if (node.translateX != 0f || node.translateY != 0f) {
                        translate(left = node.translateX, top = node.translateY)
                    }
                    if (node.rotation != 0f) {
                        rotate(degrees = node.rotation, pivot = Offset(node.pivotX, node.pivotY))
                    }
                    if (node.scaleX != 1f || node.scaleY != 1f) {
                        scale(scaleX = node.scaleX, scaleY = node.scaleY, pivot = Offset(node.pivotX, node.pivotY))
                    }
                    if (clip != null) {
                        clipPath(clip)
                    }
                }) {
                    drawNodes(node.children)
                }
            }
            is PortableVectorNode.Shape -> {
                val path = buildComposePath(
                    ops = node.ops,
                    fillType = if (node.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
                )
                node.fill?.let { brushSpec ->
                    drawPath(
                        path = path,
                        brush = brushSpec.toComposeBrush(),
                        alpha = node.fillAlpha
                    )
                }
                if (node.stroke != null && node.strokeWidth > 0f) {
                    drawPath(
                        path = path,
                        brush = node.stroke.toComposeBrush(),
                        alpha = node.strokeAlpha,
                        style = Stroke(
                            width = node.strokeWidth,
                            cap = when (node.lineCap) {
                                PortableLineCap.ROUND -> StrokeCap.Round
                                PortableLineCap.SQUARE -> StrokeCap.Square
                                PortableLineCap.BUTT -> StrokeCap.Butt
                            },
                            join = when (node.lineJoin) {
                                PortableLineJoin.ROUND -> StrokeJoin.Round
                                PortableLineJoin.BEVEL -> StrokeJoin.Bevel
                                PortableLineJoin.MITER -> StrokeJoin.Miter
                            }
                        )
                    )
                }
            }
        }
    }
}

private fun buildCombinedPath(paths: List<List<PortablePathOp>>): Path? {
    if (paths.isEmpty()) return null
    val combined = Path()
    for (ops in paths) {
        combined.addPath(buildComposePath(ops, PathFillType.NonZero))
    }
    return combined
}

private fun buildComposePath(ops: List<PortablePathOp>, fillType: PathFillType): Path {
    val path = Path()
    path.fillType = fillType
    for (op in ops) {
        when (op) {
            is PortablePathOp.MoveTo -> path.moveTo(op.x, op.y)
            is PortablePathOp.LineTo -> path.lineTo(op.x, op.y)
            is PortablePathOp.QuadTo -> path.quadraticTo(op.x1, op.y1, op.x2, op.y2)
            is PortablePathOp.CubicTo -> path.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x3, op.y3)
            PortablePathOp.Close -> path.close()
        }
    }
    return path
}

private fun PortableVectorBrush.toComposeBrush(): Brush = when (this) {
    is PortableVectorBrush.Solid -> SolidColor(argbToComposeColor(argb))
    is PortableVectorBrush.LinearGradient -> {
        val pairs = Array(stops.size) { i -> stops[i].offset to argbToComposeColor(stops[i].argb) }
        Brush.linearGradient(
            *pairs,
            start = Offset(startX, startY),
            end = Offset(endX, endY)
        )
    }
    is PortableVectorBrush.RadialGradient -> {
        val pairs = Array(stops.size) { i -> stops[i].offset to argbToComposeColor(stops[i].argb) }
        Brush.radialGradient(
            *pairs,
            center = Offset(centerX, centerY),
            radius = radius
        )
    }
}

private fun argbToComposeColor(argb: Long): Color = Color(
    red = ((argb ushr 16) and 0xFFL).toInt(),
    green = ((argb ushr 8) and 0xFFL).toInt(),
    blue = (argb and 0xFFL).toInt(),
    alpha = ((argb ushr 24) and 0xFFL).toInt()
)
