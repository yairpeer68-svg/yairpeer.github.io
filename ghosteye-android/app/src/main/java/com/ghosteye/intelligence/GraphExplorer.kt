package com.ghosteye.intelligence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class VisualNode(val id: String, val label: String, val kind: String)
private data class VisualEdge(val source: String, val target: String, val kind: String)

@Composable
fun InteractiveEntityGraph(
    graph: JSONObject,
    modifier: Modifier = Modifier,
    onNodeSelected: ((String) -> Unit)? = null
) {
    val graphKey = graph.toString()
    val nodes = remember(graphKey) {
        val arr = graph.optJSONArray("nodes") ?: JSONArray()
        buildList {
            for (i in 0 until minOf(arr.length(), 40)) {
                val n = arr.optJSONObject(i) ?: continue
                val id = n.optString("id")
                if (id.isBlank()) continue
                add(VisualNode(id, n.optString("label", n.optString("display_value", n.optString("canonical_value", id))).take(48), n.optString("kind", n.optString("entity_type", "entity"))))
            }
        }
    }
    val edges = remember(graphKey) {
        val arr = graph.optJSONArray("edges") ?: JSONArray()
        buildList {
            for (i in 0 until minOf(arr.length(), 120)) {
                val e = arr.optJSONObject(i) ?: continue
                val source = e.optString("source", e.optString("source_id"))
                val target = e.optString("target", e.optString("target_id"))
                if (source.isNotBlank() && target.isNotBlank()) add(VisualEdge(source, target, e.optString("kind", e.optString("relation_type", "related"))))
            }
        }
    }
    var selectedId by remember(graphKey) { mutableStateOf(nodes.firstOrNull()?.id) }
    val selected = nodes.firstOrNull { it.id == selectedId }
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    fun nodeColor(kind: String): Color = when (kind.lowercase()) {
        "domain", "subdomain" -> GhostEyePalette.Cyan
        "ip", "asn" -> GhostEyePalette.Violet
        "cve", "cwe", "vulnerability" -> GhostEyePalette.Rose
        "hash", "malware", "ioc" -> GhostEyePalette.Amber
        "package", "product", "vendor" -> GhostEyePalette.Emerald
        else -> scheme.primary
    }

    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainerHigh.copy(alpha = 0.76f),
            tonalElevation = 0.dp
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp)) {
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { 360.dp.toPx() }
                val center = Offset(widthPx / 2f, heightPx / 2f)
                val outerRadius = min(widthPx, heightPx) * 0.38f
                val innerRadius = outerRadius * 0.58f
                val positions = remember(nodes, selectedId, widthPx, heightPx) {
                    val focus = selectedId ?: nodes.firstOrNull()?.id
                    val others = nodes.filter { it.id != focus }
                    buildMap {
                        if (focus != null) put(focus, center)
                        others.forEachIndexed { index, node ->
                            val ring = if (index % 3 == 0) innerRadius else outerRadius
                            val angle = if (others.isEmpty()) 0.0 else (2.0 * PI * index / others.size) - PI / 2.0
                            put(node.id, Offset(center.x + (ring * cos(angle)).toFloat(), center.y + (ring * sin(angle)).toFloat()))
                        }
                    }
                }

                Canvas(
                    Modifier.fillMaxSize().pointerInput(nodes, positions) {
                        detectTapGestures { tap ->
                            val nearest = positions.minByOrNull { (_, p) -> (p - tap).getDistance() }
                            if (nearest != null && (nearest.value - tap).getDistance() <= 44f) {
                                selectedId = nearest.key
                                onNodeSelected?.invoke(nearest.key)
                            }
                        }
                    }
                ) {
                    drawCircle(scheme.primary.copy(alpha = 0.035f), radius = outerRadius * 1.12f, center = center)
                    drawCircle(scheme.secondary.copy(alpha = 0.025f), radius = innerRadius * 1.08f, center = center)
                    edges.forEach { edge ->
                        val a = positions[edge.source] ?: return@forEach
                        val b = positions[edge.target] ?: return@forEach
                        val connected = edge.source == selectedId || edge.target == selectedId
                        drawLine(
                            if (connected) scheme.primary.copy(alpha = 0.58f) else scheme.outlineVariant.copy(alpha = 0.34f),
                            a,
                            b,
                            strokeWidth = if (connected) 3f else 1.5f
                        )
                    }
                    nodes.forEach { node ->
                        val p = positions[node.id] ?: return@forEach
                        val active = node.id == selectedId
                        val color = nodeColor(node.kind)
                        if (active) drawCircle(color.copy(alpha = 0.16f), radius = 29f, center = p)
                        drawCircle(color, radius = if (active) 17f else 10f, center = p)
                        if (active || nodes.size <= 14) {
                            drawContext.canvas.nativeCanvas.drawText(
                                node.label.take(if (active) 22 else 14),
                                p.x + if (active) 23f else 14f,
                                p.y - 10f,
                                android.graphics.Paint().apply {
                                    this.color = scheme.onSurface.toArgbCompat()
                                    textSize = if (active) 30f else 24f
                                    isAntiAlias = true
                                }
                            )
                        }
                    }
                }
            }
        }
        selected?.let {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(it.kind.uppercase())
                Text(it.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            }
            Text("לחץ על node אחר כדי למקד את הגרף סביבו", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255)
)
