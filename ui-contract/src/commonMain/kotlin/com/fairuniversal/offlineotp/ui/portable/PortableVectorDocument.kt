package com.fairuniversal.offlineotp.ui.portable

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Toolkit-free, pure-Kotlin parser for the app's bundled Android `<vector>` brand artwork.
 *
 * Keeping the XML and SVG `pathData` model in `:ui-contract` (no Android, Java DOM, or Compose
 * dependencies) lets `:ui-foundation` render all 466 bundled `thumb_*.xml` marks directly on iOS
 * and shared Compose canvases while remaining verifiable on a plain JVM test runner.
 *
 * Only bundled drawables are expected, and external entities / `DOCTYPE` declarations are rejected
 * unconditionally before scanning.
 */
data class PortableVectorDocument(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val nodes: List<PortableVectorNode>
) {
    companion object {
        const val MAX_VECTOR_XML_CHARS: Int = 256 * 1024
        const val MAX_TREE_DEPTH: Int = 16
        const val MAX_PATH_OPS: Int = 16_384

        /** Parses a bundled `<vector>` XML string, or returns null when malformed or unsupported. */
        fun parse(xml: String): PortableVectorDocument? {
            if (xml.isBlank() || xml.length > MAX_VECTOR_XML_CHARS) return null
            if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
                return null
            }
            val root = XmlScanner(xml).parseRoot() ?: return null
            if (root.name != "vector") return null
            val width = (root.floatAttr("viewportWidth") ?: root.dimenAttr("width"))
                ?.takeIf { it > 0f && it.isFinite() } ?: return null
            val height = (root.floatAttr("viewportHeight") ?: root.dimenAttr("height"))
                ?.takeIf { it > 0f && it.isFinite() } ?: return null
            return PortableVectorDocument(
                viewportWidth = width,
                viewportHeight = height,
                nodes = parseChildren(root, depth = 0)
            )
        }

        private fun parseChildren(parent: XmlNode, depth: Int): List<PortableVectorNode> {
            if (depth > MAX_TREE_DEPTH) return emptyList()
            val out = ArrayList<PortableVectorNode>()
            for (child in parent.children) {
                when (child.name) {
                    "path" -> parseShape(child)?.let { out.add(it) }
                    "group" -> parseGroup(child, depth + 1)?.let { out.add(it) }
                }
            }
            return out
        }

        private fun parseGroup(element: XmlNode, depth: Int): PortableVectorNode.Group? {
            if (depth > MAX_TREE_DEPTH) return null
            val clips = element.children
                .filter { it.name == "clip-path" }
                .mapNotNull { it.attr("pathData") }
                .mapNotNull { PathDataReader(it).parse() }
            return PortableVectorNode.Group(
                rotation = element.floatAttr("rotation") ?: 0f,
                pivotX = element.floatAttr("pivotX") ?: 0f,
                pivotY = element.floatAttr("pivotY") ?: 0f,
                scaleX = element.floatAttr("scaleX") ?: 1f,
                scaleY = element.floatAttr("scaleY") ?: 1f,
                translateX = element.floatAttr("translateX") ?: 0f,
                translateY = element.floatAttr("translateY") ?: 0f,
                clipPaths = clips,
                children = parseChildren(element, depth)
            )
        }

        private fun parseShape(element: XmlNode): PortableVectorNode.Shape? {
            val data = element.attr("pathData") ?: return null
            val ops = PathDataReader(data).parse() ?: return null
            if (ops.isEmpty()) return null
            val fill = parseBrush(element, "fillColor")
            val stroke = parseBrush(element, "strokeColor")
            if (fill == null && stroke == null) return null
            val evenOdd = element.attr("fillType")?.equals("evenOdd", ignoreCase = true) == true
            val lineCap = when (element.attr("strokeLineCap")) {
                "round" -> PortableLineCap.ROUND
                "square" -> PortableLineCap.SQUARE
                else -> PortableLineCap.BUTT
            }
            val lineJoin = when (element.attr("strokeLineJoin")) {
                "round" -> PortableLineJoin.ROUND
                "bevel" -> PortableLineJoin.BEVEL
                else -> PortableLineJoin.MITER
            }
            return PortableVectorNode.Shape(
                ops = ops,
                evenOdd = evenOdd,
                fill = fill,
                fillAlpha = (element.floatAttr("fillAlpha") ?: 1f).coerceIn(0f, 1f),
                stroke = stroke,
                strokeWidth = (element.floatAttr("strokeWidth") ?: 0f).coerceAtLeast(0f),
                strokeAlpha = (element.floatAttr("strokeAlpha") ?: 1f).coerceIn(0f, 1f),
                lineCap = lineCap,
                lineJoin = lineJoin
            )
        }

        private fun parseBrush(element: XmlNode, attribute: String): PortableVectorBrush? {
            element.attr(attribute)?.let { parseHexArgb(it) }?.let { return PortableVectorBrush.Solid(it) }
            for (child in element.children) {
                if (child.name == "attr" &&
                    (child.attr("name") == "android:$attribute" || child.attr("name") == attribute)
                ) {
                    val gradient = child.children.firstOrNull { it.name == "gradient" } ?: continue
                    parseGradient(gradient)?.let { return it }
                }
            }
            return null
        }

        private fun parseGradient(gradient: XmlNode): PortableVectorBrush? {
            val rawStops = gradient.children
                .filter { it.name == "item" }
                .mapNotNull { item ->
                    val argb = item.attr("color")?.let { parseHexArgb(it) } ?: return@mapNotNull null
                    val offset = (item.floatAttr("offset") ?: 0f).coerceIn(0f, 1f)
                    PortableGradientStop(offset = offset, argb = argb)
                }
                .sortedBy { it.offset }
            if (rawStops.isEmpty()) return null
            val uniqueStops = ArrayList<PortableGradientStop>(rawStops.size)
            for (stop in rawStops) {
                if (uniqueStops.isNotEmpty() && stop.offset <= uniqueStops.last().offset) continue
                uniqueStops.add(stop)
            }
            if (uniqueStops.size == 1) return PortableVectorBrush.Solid(uniqueStops.first().argb)
            val startX = gradient.floatAttr("startX") ?: 0f
            val startY = gradient.floatAttr("startY") ?: 0f
            return when (gradient.attr("type")) {
                "radial" -> {
                    val centerX = gradient.floatAttr("centerX") ?: startX
                    val centerY = gradient.floatAttr("centerY") ?: startY
                    val radius = gradient.floatAttr("gradientRadius") ?: 0f
                    if (radius <= 0f) {
                        PortableVectorBrush.Solid(uniqueStops.last().argb)
                    } else {
                        PortableVectorBrush.RadialGradient(
                            centerX = centerX,
                            centerY = centerY,
                            radius = radius,
                            stops = uniqueStops
                        )
                    }
                }
                "sweep" -> PortableVectorBrush.Solid(uniqueStops.last().argb)
                else -> {
                    val endX = gradient.floatAttr("endX") ?: 0f
                    val endY = gradient.floatAttr("endY") ?: 0f
                    if (startX == endX && startY == endY) {
                        PortableVectorBrush.Solid(uniqueStops.last().argb)
                    } else {
                        PortableVectorBrush.LinearGradient(
                            startX = startX,
                            startY = startY,
                            endX = endX,
                            endY = endY,
                            stops = uniqueStops
                        )
                    }
                }
            }
        }

        /** Parses `#RGB`, `#ARGB`, `#RRGGBB`, or `#AARRGGBB` into an unsigned `0xAARRGGBB` Long. */
        fun parseHexArgb(value: String): Long? {
            val trimmed = value.trim()
            if (!trimmed.startsWith("#")) return null
            val hex = trimmed.substring(1)
            if (hex.isEmpty() || hex.length > 8 || !hex.all { isHexDigit(it) }) return null
            val expanded = when (hex.length) {
                3, 4 -> buildString(hex.length * 2) {
                    for (ch in hex) {
                        append(ch)
                        append(ch)
                    }
                }
                6, 8 -> hex
                else -> return null
            }
            val parsed = expanded.toLongOrNull(16) ?: return null
            return if (expanded.length == 6) (0xFF000000L or parsed) else (parsed and 0xFFFFFFFFL)
        }

        private fun isHexDigit(ch: Char): Boolean =
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
    }
}

sealed interface PortableVectorNode {
    data class Group(
        val rotation: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val clipPaths: List<List<PortablePathOp>> = emptyList(),
        val children: List<PortableVectorNode> = emptyList()
    ) : PortableVectorNode

    data class Shape(
        val ops: List<PortablePathOp>,
        val evenOdd: Boolean,
        val fill: PortableVectorBrush?,
        val fillAlpha: Float,
        val stroke: PortableVectorBrush?,
        val strokeWidth: Float,
        val strokeAlpha: Float,
        val lineCap: PortableLineCap,
        val lineJoin: PortableLineJoin
    ) : PortableVectorNode
}

sealed interface PortableVectorBrush {
    data class Solid(val argb: Long) : PortableVectorBrush

    data class LinearGradient(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val stops: List<PortableGradientStop>
    ) : PortableVectorBrush

    data class RadialGradient(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val stops: List<PortableGradientStop>
    ) : PortableVectorBrush
}

data class PortableGradientStop(
    val offset: Float,
    val argb: Long
)

enum class PortableLineCap { BUTT, ROUND, SQUARE }

enum class PortableLineJoin { MITER, ROUND, BEVEL }

sealed interface PortablePathOp {
    data class MoveTo(val x: Float, val y: Float) : PortablePathOp
    data class LineTo(val x: Float, val y: Float) : PortablePathOp
    data class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PortablePathOp
    data class CubicTo(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x3: Float,
        val y3: Float
    ) : PortablePathOp
    object Close : PortablePathOp
}

private class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>
) {
    fun attr(localName: String): String? = attributes[localName]?.takeIf { it.isNotEmpty() }

    fun floatAttr(localName: String): Float? = attr(localName)?.trim()?.toFloatOrNull()

    fun dimenAttr(localName: String): Float? {
        val raw = attr(localName)?.trim() ?: return null
        val stripped = when {
            raw.endsWith("dip", ignoreCase = true) -> raw.dropLast(3)
            raw.endsWith("dp", ignoreCase = true) ||
                raw.endsWith("px", ignoreCase = true) ||
                raw.endsWith("sp", ignoreCase = true) -> raw.dropLast(2)
            else -> raw
        }
        return stripped.trim().toFloatOrNull()
    }
}

private class XmlScanner(private val text: String) {
    private var pos = 0

    fun parseRoot(): XmlNode? {
        skipMisc()
        if (pos >= text.length) return null
        return parseElement(depth = 0)
    }

    private fun skipMisc() {
        while (pos < text.length) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (text.startsWith("<?", pos)) {
                val end = text.indexOf("?>", pos + 2)
                if (end < 0) { pos = text.length; return }
                pos = end + 2
            } else if (text.startsWith("<!--", pos)) {
                val end = text.indexOf("-->", pos + 4)
                if (end < 0) { pos = text.length; return }
                pos = end + 3
            } else {
                break
            }
        }
    }

    private fun parseElement(depth: Int): XmlNode? {
        if (depth > PortableVectorDocument.MAX_TREE_DEPTH + 2) return null
        if (pos >= text.length || text[pos] != '<') return null
        if (text.startsWith("</", pos) || text.startsWith("<!", pos) || text.startsWith("<?", pos)) return null
        pos++ // '<'
        val rawTag = readName() ?: return null
        val localTag = stripPrefix(rawTag)
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (pos < text.length) {
            skipWhitespace()
            if (pos >= text.length) return null
            if (text.startsWith("/>", pos)) {
                selfClosing = true
                pos += 2
                break
            }
            if (text[pos] == '>') {
                pos++
                break
            }
            val attrName = readName() ?: return null
            skipWhitespace()
            if (pos >= text.length || text[pos] != '=') return null
            pos++
            skipWhitespace()
            val attrVal = readQuotedValue() ?: return null
            if (attrName != "xmlns" && !attrName.startsWith("xmlns:")) {
                val key = if (attrName.startsWith("android:")) attrName.removePrefix("android:") else stripPrefix(attrName)
                attrs[key] = attrVal
            }
        }
        if (selfClosing) return XmlNode(localTag, attrs, emptyList())
        val children = ArrayList<XmlNode>()
        while (pos < text.length) {
            skipMisc()
            if (pos >= text.length) return null
            if (text.startsWith("</", pos)) {
                pos += 2
                val closeName = readName() ?: return null
                if (closeName != rawTag && stripPrefix(closeName) != localTag) return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != '>') return null
                pos++
                return XmlNode(localTag, attrs, children)
            }
            if (text[pos] == '<') {
                val child = parseElement(depth + 1) ?: return null
                children.add(child)
            } else {
                // Skip non-element text content between tags.
                val nextTag = text.indexOf('<', pos)
                if (nextTag < 0) return null
                pos = nextTag
            }
        }
        return null
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun readName(): String? {
        val start = pos
        while (pos < text.length) {
            val ch = text[pos]
            if (ch.isLetterOrDigit() || ch == ':' || ch == '_' || ch == '-' || ch == '.') {
                pos++
            } else {
                break
            }
        }
        return if (pos > start) text.substring(start, pos) else null
    }

    private fun readQuotedValue(): String? {
        if (pos >= text.length) return null
        val quote = text[pos]
        if (quote != '"' && quote != '\'') return null
        pos++
        val start = pos
        val end = text.indexOf(quote, start)
        if (end < 0) return null
        pos = end + 1
        val raw = text.substring(start, end)
        return if ('&' in raw) decodeXmlEntities(raw) else raw
    }

    private fun stripPrefix(qName: String): String {
        val colon = qName.indexOf(':')
        return if (colon >= 0 && colon + 1 < qName.length) qName.substring(colon + 1) else qName
    }

    private fun decodeXmlEntities(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '&') {
                val semi = raw.indexOf(';', i + 1)
                if (semi > i + 1) {
                    val entity = raw.substring(i + 1, semi)
                    val decoded: Char? = when {
                        entity == "amp" -> '&'
                        entity == "lt" -> '<'
                        entity == "gt" -> '>'
                        entity == "quot" -> '"'
                        entity == "apos" -> '\''
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.substring(2).toIntOrNull(16)?.takeIf { it in 0..0xFFFF }?.toChar()
                        entity.startsWith("#") ->
                            entity.substring(1).toIntOrNull()?.takeIf { it in 0..0xFFFF }?.toChar()
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }
}

private class PathDataReader(private val data: String) {
    private var index = 0
    private var currentX = 0.0
    private var currentY = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var lastControlX = 0.0
    private var lastControlY = 0.0
    private var lastCommand = ' '
    private val ops = ArrayList<PortablePathOp>()

    fun parse(): List<PortablePathOp>? {
        if (data.isBlank()) return null
        val firstCommand = data.firstOrNull { it.isLetter() }
        if (firstCommand != null && firstCommand.uppercaseChar() != 'M') {
            if (!emit(PortablePathOp.MoveTo(0f, 0f))) return null
        }
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
                command = 'L'
            } else if (command == 'm') {
                command = 'l'
            }
            if (!readCommand(command)) return null
        }
        return ops
    }

    private fun emit(op: PortablePathOp): Boolean {
        if (ops.size >= PortableVectorDocument.MAX_PATH_OPS) return false
        ops.add(op)
        return true
    }

    private fun readCommand(command: Char): Boolean {
        val relative = command.isLowerCase()
        when (command.uppercaseChar()) {
            'Z' -> {
                if (!emit(PortablePathOp.Close)) return false
                currentX = startX
                currentY = startY
                lastCommand = command
                return true
            }
            'M', 'L', 'T' -> {
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val targetX = if (relative) currentX + x else x.toDouble()
                val targetY = if (relative) currentY + y else y.toDouble()
                val ok = when (command.uppercaseChar()) {
                    'M' -> {
                        startX = targetX
                        startY = targetY
                        emit(PortablePathOp.MoveTo(targetX.toFloat(), targetY.toFloat()))
                    }
                    'T' -> {
                        val reflect = lastCommand.uppercaseChar() == 'Q' || lastCommand.uppercaseChar() == 'T'
                        val controlX = if (reflect) 2.0 * currentX - lastControlX else currentX
                        val controlY = if (reflect) 2.0 * currentY - lastControlY else currentY
                        lastControlX = controlX
                        lastControlY = controlY
                        emit(
                            PortablePathOp.QuadTo(
                                controlX.toFloat(),
                                controlY.toFloat(),
                                targetX.toFloat(),
                                targetY.toFloat()
                            )
                        )
                    }
                    else -> emit(PortablePathOp.LineTo(targetX.toFloat(), targetY.toFloat()))
                }
                if (!ok) return false
                lastCommand = command
                currentX = targetX
                currentY = targetY
                return true
            }
            'H', 'V' -> {
                val value = nextNumber() ?: return false
                if (command.uppercaseChar() == 'H') {
                    currentX = if (relative) currentX + value else value.toDouble()
                } else {
                    currentY = if (relative) currentY + value else value.toDouble()
                }
                if (!emit(PortablePathOp.LineTo(currentX.toFloat(), currentY.toFloat()))) return false
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
                if (!emit(
                        PortablePathOp.CubicTo(
                            c1x.toFloat(), c1y.toFloat(),
                            c2x.toFloat(), c2y.toFloat(),
                            tx.toFloat(), ty.toFloat()
                        )
                    )
                ) return false
                lastControlX = c2x
                lastControlY = c2y
                lastCommand = command
                currentX = tx
                currentY = ty
                return true
            }
            'S' -> {
                val x2 = nextNumber() ?: return false
                val y2 = nextNumber() ?: return false
                val x = nextNumber() ?: return false
                val y = nextNumber() ?: return false
                val reflect = lastCommand.uppercaseChar() == 'C' || lastCommand.uppercaseChar() == 'S'
                val c1x = if (reflect) 2.0 * currentX - lastControlX else currentX
                val c1y = if (reflect) 2.0 * currentY - lastControlY else currentY
                val c2x = if (relative) currentX + x2 else x2.toDouble()
                val c2y = if (relative) currentY + y2 else y2.toDouble()
                val tx = if (relative) currentX + x else x.toDouble()
                val ty = if (relative) currentY + y else y.toDouble()
                if (!emit(
                        PortablePathOp.CubicTo(
                            c1x.toFloat(), c1y.toFloat(),
                            c2x.toFloat(), c2y.toFloat(),
                            tx.toFloat(), ty.toFloat()
                        )
                    )
                ) return false
                lastControlX = c2x
                lastControlY = c2y
                lastCommand = command
                currentX = tx
                currentY = ty
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
                if (!emit(PortablePathOp.QuadTo(cx.toFloat(), cy.toFloat(), tx.toFloat(), ty.toFloat()))) {
                    return false
                }
                lastControlX = cx
                lastControlY = cy
                lastCommand = command
                currentX = tx
                currentY = ty
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
                if (!appendArc(rx.toDouble(), ry.toDouble(), rotation.toDouble(), largeArc, sweep, tx, ty)) {
                    return false
                }
                lastCommand = command
                currentX = tx
                currentY = ty
                return true
            }
            else -> return false
        }
    }

    private fun appendArc(
        rxInput: Double,
        ryInput: Double,
        rotationDegrees: Double,
        largeArc: Boolean,
        sweep: Boolean,
        endX: Double,
        endY: Double
    ): Boolean {
        var rx = abs(rxInput)
        var ry = abs(ryInput)
        if (rx == 0.0 || ry == 0.0 || (currentX == endX && currentY == endY)) {
            return emit(PortablePathOp.LineTo(endX.toFloat(), endY.toFloat()))
        }
        val phi = (rotationDegrees % 360.0) * (PI / 180.0)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx2 = (currentX - endX) / 2.0
        val dy2 = (currentY - endY) / 2.0
        val x1 = cosPhi * dx2 + sinPhi * dy2
        val y1 = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1.0) {
            val scale = sqrt(lambda)
            rx *= scale
            ry *= scale
        }
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val ratio = if (denominator == 0.0) 0.0 else max(0.0, numerator / denominator)
        val coefficient = sqrt(ratio) * if (largeArc == sweep) -1.0 else 1.0
        val cx1 = coefficient * rx * y1 / ry
        val cy1 = -coefficient * ry * x1 / rx
        val centerX = cosPhi * cx1 - sinPhi * cy1 + (currentX + endX) / 2.0
        val centerY = sinPhi * cx1 + cosPhi * cy1 + (currentY + endY) / 2.0
        val startVectorX = (x1 - cx1) / rx
        val startVectorY = (y1 - cy1) / ry
        val endVectorX = (-x1 - cx1) / rx
        val endVectorY = (-y1 - cy1) / ry
        val startAngle = atan2(startVectorY, startVectorX)
        var deltaAngle = atan2(
            startVectorX * endVectorY - startVectorY * endVectorX,
            startVectorX * endVectorX + startVectorY * endVectorY
        )
        if (!sweep && deltaAngle > 0.0) deltaAngle -= 2.0 * PI
        if (sweep && deltaAngle < 0.0) deltaAngle += 2.0 * PI

        val segments = max(1, ceil(abs(deltaAngle) / (PI / 2.0)).toInt())
        val step = deltaAngle / segments
        val handle = 4.0 / 3.0 * tan(step / 4.0)
        for (segment in 0 until segments) {
            val from = startAngle + segment * step
            val to = from + step
            val sx = centerX + rx * cos(from) * cosPhi - ry * sin(from) * sinPhi
            val sy = centerY + rx * cos(from) * sinPhi + ry * sin(from) * cosPhi
            val ex = centerX + rx * cos(to) * cosPhi - ry * sin(to) * sinPhi
            val ey = centerY + rx * cos(to) * sinPhi + ry * sin(to) * cosPhi
            val sdx = -rx * sin(from) * cosPhi - ry * cos(from) * sinPhi
            val sdy = -rx * sin(from) * sinPhi + ry * cos(from) * cosPhi
            val edx = -rx * sin(to) * cosPhi - ry * cos(to) * sinPhi
            val edy = -rx * sin(to) * sinPhi + ry * cos(to) * cosPhi
            if (!emit(
                    PortablePathOp.CubicTo(
                        (sx + handle * sdx).toFloat(),
                        (sy + handle * sdy).toFloat(),
                        (ex - handle * edx).toFloat(),
                        (ey - handle * edy).toFloat(),
                        ex.toFloat(),
                        ey.toFloat()
                    )
                )
            ) return false
        }
        return true
    }

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

    private fun nextNumber(): Float? {
        skipSeparators()
        if (index >= data.length) return null
        val start = index
        if (data[index] == '+' || data[index] == '-') index++
        var digits = false
        while (index < data.length && data[index].isDigit()) {
            index++
            digits = true
        }
        if (index < data.length && data[index] == '.') {
            index++
            while (index < data.length && data[index].isDigit()) {
                index++
                digits = true
            }
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
            while (index < data.length && data[index].isDigit()) {
                index++
                exponentDigits = true
            }
            if (!exponentDigits) index = exponentStart
        }
        return data.substring(start, index).toFloatOrNull()
    }
}
