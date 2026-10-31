package org.shadowice.flocke.andotp.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Renders the app's bundled Android vector drawables (`<vector android:pathData=…>`) with Java2D.
 *
 * The desktop client shares the Android brand art rather than shipping a second icon set, so this
 * is a small, self-contained subset of Android's VectorDrawable: paths with solid or gradient
 * fills, strokes, groups (translate/scale/rotate/pivot) and clip paths. Unknown elements and
 * unusable gradient stops are skipped instead of failing the whole mark.
 *
 * Only bundled artwork is parsed. The document builder is still configured without DTD or
 * external-entity support so a malformed asset can never reach the filesystem or network.
 */
internal class VectorArtwork private constructor(
    private val viewportWidth: Float,
    private val viewportHeight: Float,
    private val nodes: List<ArtNode>
) {
    /** Rasterizes the artwork into a transparent [width] x [height] image, aspect-fit and centered. */
    fun render(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(maxOf(width, 1), maxOf(height, 1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            if (viewportWidth > 0f && viewportHeight > 0f) {
                val scale = minOf(image.width / viewportWidth, image.height / viewportHeight)
                g.translate((image.width - viewportWidth * scale) / 2.0, (image.height - viewportHeight * scale) / 2.0)
                g.scale(scale.toDouble(), scale.toDouble())
            }
            paint(g, nodes)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun paint(g: Graphics2D, children: List<ArtNode>) {
        for (node in children) when (node) {
            is Group -> {
                val saved = g.transform
                g.transform(node.transform)
                if (node.clip != null) g.clip(node.clip)
                paint(g, node.children)
                g.transform = saved
            }
            is Shape -> {
                node.fill?.let { brush ->
                    g.paint = brush.paint(node.fillAlpha)
                    g.fill(node.path)
                }
                node.stroke?.let { brush ->
                    if (node.strokeWidth > 0f) {
                        g.paint = brush.paint(node.strokeAlpha)
                        g.stroke = BasicStroke(node.strokeWidth, node.lineCap, node.lineJoin)
                        g.draw(node.path)
                    }
                }
            }
        }
    }

    private class Group(
        val transform: AffineTransform,
        val clip: Path2D?,
        val children: List<ArtNode>
    ) : ArtNode

    private class Shape(
        val path: Path2D,
        val fill: Brush?,
        val fillAlpha: Float,
        val stroke: Brush?,
        val strokeWidth: Float,
        val strokeAlpha: Float,
        val lineCap: Int,
        val lineJoin: Int
    ) : ArtNode

    private sealed interface ArtNode

    /** A fill or stroke color source: a solid color or one of Android's gradient types. */
    private sealed interface Brush {
        fun paint(alpha: Float): Paint

        class Solid(private val color: Color) : Brush {
            override fun paint(alpha: Float): Paint = withAlpha(color, alpha)
        }

        class Linear(
            private val start: Point2D.Float,
            private val end: Point2D.Float,
            private val fractions: FloatArray,
            private val colors: Array<Color>
        ) : Brush {
            override fun paint(alpha: Float): Paint {
                if (colors.size < 2 || start.distance(end) <= 0.0) return Solid(colors.last()).paint(alpha)
                return try {
                    LinearGradientPaint(start, end, fractions, colors.map { withAlpha(it, alpha) }.toTypedArray())
                } catch (invalid: IllegalArgumentException) {
                    Solid(colors.last()).paint(alpha)
                }
            }
        }

        class Radial(
            private val center: Point2D.Float,
            private val radius: Float,
            private val fractions: FloatArray,
            private val colors: Array<Color>
        ) : Brush {
            override fun paint(alpha: Float): Paint {
                if (colors.size < 2 || radius <= 0f) return Solid(colors.last()).paint(alpha)
                return try {
                    RadialGradientPaint(center, radius, fractions, colors.map { withAlpha(it, alpha) }.toTypedArray())
                } catch (invalid: IllegalArgumentException) {
                    Solid(colors.last()).paint(alpha)
                }
            }
        }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Parses [input]; returns null for anything that is not a usable `<vector>` document. */
        fun parse(input: InputStream): VectorArtwork? = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val reader = factory.newDocumentBuilder().apply {
                // Rejections are reported by returning null; never print XML parser noise.
                setErrorHandler(object : org.xml.sax.ErrorHandler {
                    override fun warning(problem: org.xml.sax.SAXParseException) = Unit
                    override fun error(problem: org.xml.sax.SAXParseException) = throw problem
                    override fun fatalError(problem: org.xml.sax.SAXParseException) = throw problem
                })
            }
            val root = reader.parse(input).documentElement
            fromVector(root)
        } catch (failure: Exception) {
            null
        }

        /** Convenience for the loader: rasterize [input] at [size] or return null. */
        fun render(input: InputStream, size: Int): BufferedImage? =
            parse(input)?.render(size, size)

        private fun fromVector(root: Element): VectorArtwork? {
            if (root.tagName != "vector") return null
            val width = (root.float("viewportWidth") ?: root.float("width"))?.takeIf { it > 0f } ?: return null
            val height = (root.float("viewportHeight") ?: root.float("height"))?.takeIf { it > 0f } ?: return null
            return VectorArtwork(width, height, children(root))
        }

        private fun children(parent: Element): List<ArtNode> {
            val result = ArrayList<ArtNode>()
            var child = parent.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val element = child as Element
                    when (element.tagName) {
                        "path" -> shape(element)?.let { result.add(it) }
                        "group" -> result.add(group(element))
                    }
                }
                child = child.nextSibling
            }
            return result
        }

        private fun shape(element: Element): Shape? {
            val data = element.string("pathData") ?: return null
            val evenOdd = element.string("fillType").equals("evenOdd", ignoreCase = true)
            val path = PathDataParser(data, if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO).parse()
            if (path == null) return null
            val fill = brush(element, "fillColor")
            val stroke = brush(element, "strokeColor")
            if (fill == null && stroke == null) return null
            return Shape(
                path = path,
                fill = fill,
                fillAlpha = element.float("fillAlpha") ?: 1f,
                stroke = stroke,
                strokeWidth = element.float("strokeWidth") ?: 0f,
                strokeAlpha = element.float("strokeAlpha") ?: 1f,
                lineCap = when (element.string("strokeLineCap")) {
                    "round" -> BasicStroke.CAP_ROUND
                    "square" -> BasicStroke.CAP_SQUARE
                    else -> BasicStroke.CAP_BUTT
                },
                lineJoin = when (element.string("strokeLineJoin")) {
                    "round" -> BasicStroke.JOIN_ROUND
                    "bevel" -> BasicStroke.JOIN_BEVEL
                    else -> BasicStroke.JOIN_MITER
                }
            )
        }

        private fun group(element: Element): Group {
            val pivotX = element.float("pivotX") ?: 0f
            val pivotY = element.float("pivotY") ?: 0f
            // Same composition as Android's VGroup: move by -pivot, scale, rotate, then translate.
            val transform = AffineTransform()
            transform.translate((pivotX + (element.float("translateX") ?: 0f)).toDouble(),
                (pivotY + (element.float("translateY") ?: 0f)).toDouble())
            val rotation = element.float("rotation") ?: 0f
            if (rotation != 0f) transform.rotate(Math.toRadians(rotation.toDouble()))
            transform.scale((element.float("scaleX") ?: 1f).toDouble(), (element.float("scaleY") ?: 1f).toDouble())
            transform.translate(-pivotX.toDouble(), -pivotY.toDouble())
            val clip = element.childrenOf("clip-path")
                .mapNotNull { it.string("pathData") }
                .mapNotNull { PathDataParser(it, Path2D.WIND_NON_ZERO).parse() }
                .reduceOrNull { combined, next -> combined.apply { append(next, false) } }
            return Group(transform, clip, children(element))
        }

        /** Solid or `<aapt:attr name="android:fillColor"><gradient …>` brush for [attribute]. */
        private fun brush(element: Element, attribute: String): Brush? {
            element.color(attribute)?.let { return Brush.Solid(it) }
            var child = element.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val nested = child as Element
                    val name = nested.getAttribute("name")
                    if (name == "android:$attribute") {
                        nested.gradient()?.let { return it }
                    }
                }
                child = child.nextSibling
            }
            return null
        }

        private fun Element.gradient(): Brush? {
            val gradient = childrenOf("gradient").firstOrNull() ?: return null
            val stops = gradient.childrenOf("item").mapNotNull { item ->
                val color = item.color("color") ?: return@mapNotNull null
                val offset = item.float("offset") ?: 0f
                offset to color
            }
            if (stops.isEmpty()) return null
            val sorted = stops.sortedBy { it.first }
            val fractions = sorted.map { it.first.coerceIn(0f, 1f) }.toFloatArray()
            val colors = sorted.map { it.second }.toTypedArray()
            // Java2D rejects duplicate offsets; keep the first stop at each position.
            val uniqueFractions = ArrayList<Float>()
            val uniqueColors = ArrayList<Color>()
            for (index in fractions.indices) {
                if (uniqueFractions.isNotEmpty() && fractions[index] <= uniqueFractions.last()) continue
                uniqueFractions.add(fractions[index])
                uniqueColors.add(colors[index])
            }
            if (uniqueFractions.size == 1) return Brush.Solid(uniqueColors.first())
            val x0 = gradient.float("startX") ?: 0f
            val y0 = gradient.float("startY") ?: 0f
            return when (gradient.string("type")) {
                "radial" -> {
                    val centerX = gradient.float("centerX") ?: x0
                    val centerY = gradient.float("centerY") ?: y0
                    val radius = gradient.float("gradientRadius") ?: 0f
                    Brush.Radial(Point2D.Float(centerX, centerY), radius,
                        uniqueFractions.toFloatArray(), uniqueColors.toTypedArray())
                }
                "sweep" -> Brush.Solid(uniqueColors.last())
                else -> {
                    val x1 = gradient.float("endX") ?: 0f
                    val y1 = gradient.float("endY") ?: 0f
                    Brush.Linear(Point2D.Float(x0, y0), Point2D.Float(x1, y1),
                        uniqueFractions.toFloatArray(), uniqueColors.toTypedArray())
                }
            }
        }

        private fun withAlpha(color: Color, alpha: Float): Color {
            val scaled = (color.alpha * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            return Color(color.red, color.green, color.blue, scaled)
        }

        private fun Element.childrenOf(tag: String): List<Element> {
            val result = ArrayList<Element>()
            var child = firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tag) result.add(child)
                child = child.nextSibling
            }
            return result
        }

        private fun Element.string(name: String): String? =
            getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

        private fun Element.float(name: String): Float? = string(name)?.trim()?.toFloatOrNull()

        /** `#RGB`, `#ARGB`, `#RRGGBB` and `#AARRGGBB`; resource references have no value here. */
        private fun Element.color(name: String): Color? = string(name)?.let { parseColor(it) }

        private fun parseColor(value: String): Color? {
            val text = value.trim()
            if (!text.startsWith("#")) return null
            val hex = text.substring(1)
            if (hex.isEmpty() || hex.length > 8 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            val expanded = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                else -> hex
            }
            return when (expanded.length) {
                6 -> Color(expanded.toInt(16) or 0xFF000000.toInt(), true)
                8 -> Color(expanded.toLong(16).toInt(), true)
                else -> null
            }
        }
    }
}

/**
 * Minimal SVG/Android path-data reader: `M L H V C S Q T A Z` plus the relative forms. Android
 * path data is SVG path syntax, so the drawing model maps directly onto [Path2D].
 */
internal class PathDataParser(private val data: String, private val windingRule: Int) {
    private var index = 0
    private var currentX = 0.0
    private var currentY = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var lastControlX = 0.0
    private var lastControlY = 0.0
    private var lastCommand = ' '

    fun parse(): Path2D? {
        val path = Path2D.Double(windingRule)
        if (data.isBlank()) return null
        // Some generated marks open with a drawing command (e.g. "H0V337H337V0z"); the implicit
        // starting point is the origin, which Path2D requires to be an explicit moveto.
        val firstCommand = data.firstOrNull { it.isLetter() }
        if (firstCommand != null && firstCommand.uppercaseChar() != 'M') path.moveTo(0.0, 0.0)
        var command = ' '
        while (true) {
            skipSeparators()
            if (index >= data.length) break
            val next = data[index]
            if (next.isLetter()) {
                command = next
                index++
            } else if (command == ' ') {
                return null
            } else if (command == 'M') {
                command = 'L' // implicit repetitions of moveto are linetos
            } else if (command == 'm') {
                command = 'l'
            }
            if (!drawCommand(path, command)) return null
        }
        return path
    }

    private fun drawCommand(path: Path2D, command: Char): Boolean {
        val relative = command.isLowerCase()
        when (command.uppercaseChar()) {
            'Z' -> {
                path.closePath()
                currentX = startX; currentY = startY
                lastCommand = command
                return true
            }
            'M', 'L', 'T' -> {
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val targetX = if (relative) currentX + x else x.toDouble()
                val targetY = if (relative) currentY + y else y.toDouble()
                when (command.uppercaseChar()) {
                    'M' -> {
                        path.moveTo(targetX, targetY)
                        startX = targetX; startY = targetY
                    }
                    'T' -> {
                        // The control point mirrors the previous quadratic control point.
                        val reflect = lastCommand.uppercaseChar() == 'Q' || lastCommand.uppercaseChar() == 'T'
                        val controlX = if (reflect) 2 * currentX - lastControlX else currentX
                        val controlY = if (reflect) 2 * currentY - lastControlY else currentY
                        path.quadTo(controlX, controlY, targetX, targetY)
                        lastControlX = controlX; lastControlY = controlY
                    }
                    else -> path.lineTo(targetX, targetY)
                }
                lastCommand = command
                currentX = targetX; currentY = targetY
                return true
            }
            'H', 'V' -> {
                val value = nextNumber() ?: return false
                if (command.uppercaseChar() == 'H') {
                    currentX = if (relative) currentX + value else value.toDouble()
                    path.lineTo(currentX, currentY)
                } else {
                    currentY = if (relative) currentY + value else value.toDouble()
                    path.lineTo(currentX, currentY)
                }
                lastCommand = command
                return true
            }
            'C' -> {
                val x1 = nextNumber() ?: return false
                val y1 = nextNumber() ?: return false
                val x2 = nextNumber() ?: return false
                val y2 = nextNumber() ?: return false
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val c1x = if (relative) currentX + x1 else x1.toDouble()
                val c1y = if (relative) currentY + y1 else y1.toDouble()
                val c2x = if (relative) currentX + x2 else x2.toDouble()
                val c2y = if (relative) currentY + y2 else y2.toDouble()
                val tx = if (relative) currentX + x else x.toDouble()
                val ty = if (relative) currentY + y else y.toDouble()
                path.curveTo(c1x, c1y, c2x, c2y, tx, ty)
                lastControlX = c2x; lastControlY = c2y
                lastCommand = command
                currentX = tx; currentY = ty
                return true
            }
            'S' -> {
                val x2 = nextNumber() ?: return false
                val y2 = nextNumber() ?: return false
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val reflected = if (lastCommand.uppercaseChar() in "CS") {
                    doubleArrayOf(2 * currentX - lastControlX, 2 * currentY - lastControlY)
                } else {
                    doubleArrayOf(currentX, currentY)
                }
                val c2x = if (relative) currentX + x2 else x2.toDouble()
                val c2y = if (relative) currentY + y2 else y2.toDouble()
                val tx = if (relative) currentX + x else x.toDouble()
                val ty = if (relative) currentY + y else y.toDouble()
                path.curveTo(reflected[0], reflected[1], c2x, c2y, tx, ty)
                lastControlX = c2x; lastControlY = c2y
                lastCommand = command
                currentX = tx; currentY = ty
                return true
            }
            'Q' -> {
                val x1 = nextNumber() ?: return false
                val y1 = nextNumber() ?: return false
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val cx = if (relative) currentX + x1 else x1.toDouble()
                val cy = if (relative) currentY + y1 else y1.toDouble()
                val tx = if (relative) currentX + x else x.toDouble()
                val ty = if (relative) currentY + y else y.toDouble()
                path.quadTo(cx, cy, tx, ty)
                lastControlX = cx; lastControlY = cy
                lastCommand = command
                currentX = tx; currentY = ty
                return true
            }
            'A' -> {
                val rx = nextNumber() ?: return false
                val ry = nextNumber() ?: return false
                val rotation = nextNumber() ?: return false
                val largeArc = nextFlag() ?: return false
                val sweep = nextFlag() ?: return false
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val tx = if (relative) currentX + x else x.toDouble()
                val ty = if (relative) currentY + y else y.toDouble()
                appendArc(path, rx.toDouble(), ry.toDouble(), rotation.toDouble(), largeArc, sweep, tx, ty)
                lastCommand = command
                currentX = tx; currentY = ty
                return true
            }
            else -> return false
        }
    }

    /** SVG endpoint-to-center conversion (spec F.6.5) with cubic segments of at most 90°. */
    private fun appendArc(
        path: Path2D, rxInput: Double, ryInput: Double, rotationDegrees: Double,
        largeArc: Boolean, sweep: Boolean, endX: Double, endY: Double
    ) {
        var rx = Math.abs(rxInput)
        var ry = Math.abs(ryInput)
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(endX, endY)
            return
        }
        val phi = Math.toRadians(rotationDegrees % 360.0)
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)
        val dx2 = (currentX - endX) / 2.0
        val dy2 = (currentY - endY) / 2.0
        val x1 = cosPhi * dx2 + sinPhi * dy2
        val y1 = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1.0) {
            val scale = Math.sqrt(lambda)
            rx *= scale
            ry *= scale
        }
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val coefficient = Math.sqrt(maxOf(0.0, numerator / denominator)) * if (largeArc == sweep) -1.0 else 1.0
        val cx1 = coefficient * rx * y1 / ry
        val cy1 = -coefficient * ry * x1 / rx
        val centerX = cosPhi * cx1 - sinPhi * cy1 + (currentX + endX) / 2.0
        val centerY = sinPhi * cx1 + cosPhi * cy1 + (currentY + endY) / 2.0
        val startVectorX = (x1 - cx1) / rx
        val startVectorY = (y1 - cy1) / ry
        val endVectorX = (-x1 - cx1) / rx
        val endVectorY = (-y1 - cy1) / ry
        val startAngle = Math.atan2(startVectorY, startVectorX)
        var deltaAngle = Math.atan2(startVectorX * endVectorY - startVectorY * endVectorX,
            startVectorX * endVectorX + startVectorY * endVectorY)
        if (!sweep && deltaAngle > 0) deltaAngle -= 2 * Math.PI
        if (sweep && deltaAngle < 0) deltaAngle += 2 * Math.PI

        val segments = maxOf(1, Math.ceil(Math.abs(deltaAngle) / (Math.PI / 2)).toInt())
        val step = deltaAngle / segments
        val handle = 4.0 / 3.0 * Math.tan(step / 4.0)
        for (segment in 0 until segments) {
            val from = startAngle + segment * step
            val to = from + step
            val start = ellipsePoint(centerX, centerY, rx, ry, cosPhi, sinPhi, from)
            val end = ellipsePoint(centerX, centerY, rx, ry, cosPhi, sinPhi, to)
            val startDerivative = ellipseDerivative(rx, ry, cosPhi, sinPhi, from)
            val endDerivative = ellipseDerivative(rx, ry, cosPhi, sinPhi, to)
            path.curveTo(
                start[0] + handle * startDerivative[0], start[1] + handle * startDerivative[1],
                end[0] - handle * endDerivative[0], end[1] - handle * endDerivative[1],
                end[0], end[1]
            )
        }
    }

    private fun ellipsePoint(cx: Double, cy: Double, rx: Double, ry: Double,
                             cosPhi: Double, sinPhi: Double, angle: Double): DoubleArray =
        doubleArrayOf(
            cx + rx * Math.cos(angle) * cosPhi - ry * Math.sin(angle) * sinPhi,
            cy + rx * Math.cos(angle) * sinPhi + ry * Math.sin(angle) * cosPhi
        )

    private fun ellipseDerivative(rx: Double, ry: Double, cosPhi: Double, sinPhi: Double,
                                  angle: Double): DoubleArray =
        doubleArrayOf(
            -rx * Math.sin(angle) * cosPhi - ry * Math.cos(angle) * sinPhi,
            -rx * Math.sin(angle) * sinPhi + ry * Math.cos(angle) * cosPhi
        )

    /** Arc flags are the characters `0` and `1`, and may be separated by nothing at all. */
    private fun nextFlag(): Boolean? {
        skipSeparators()
        if (index >= data.length) return null
        return when (data[index]) {
            '0' -> { index++; false }
            '1' -> { index++; true }
            else -> null
        }
    }

    private fun skipSeparators() {
        while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
    }

    /** Reads one SVG number, tolerating `-` separators and exponents such as `1.2e-3`. */
    private fun nextNumber(): Float? {
        skipSeparators()
        if (index >= data.length) return null
        val start = index
        if (data[index] == '+' || data[index] == '-') index++
        var digits = false
        while (index < data.length && data[index].isDigit()) { index++; digits = true }
        if (index < data.length && data[index] == '.') {
            index++
            while (index < data.length && data[index].isDigit()) { index++; digits = true }
        }
        if (!digits) {
            index = start
            return null
        }
        if (index < data.length && (data[index] == 'e' || data[index] == 'E')) {
            val exponentStart = index
            index++
            if (index < data.length && (data[index] == '+' || data[index] == '-')) index++
            var exponentDigits = false
            while (index < data.length && data[index].isDigit()) { index++; exponentDigits = true }
            if (!exponentDigits) index = exponentStart
        }
        return data.substring(start, index).toFloatOrNull()
    }
}
