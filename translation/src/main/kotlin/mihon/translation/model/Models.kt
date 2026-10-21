package mihon.translation.model

import kotlinx.serialization.Serializable

/** Axis aligned box in strip coordinates (pages scaled to [mihon.translation.image.Strip.WIDTH]). */
@Serializable
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height
    val centerY get() = (top + bottom) / 2

    fun union(other: Box) = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun intersection(other: Box): Float {
        val w = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val h = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return w * h
    }

    fun iou(other: Box): Float {
        val inter = intersection(other)
        val union = area + other.area - inter
        return if (union > 0) inter / union else 0f
    }

    /** Fraction of [inner] covered by this box. */
    fun covers(inner: Box): Float = if (inner.area > 0) intersection(inner) / inner.area else 0f

    fun inset(dx: Float, dy: Float) = Box(left + dx, top + dy, right - dx, bottom - dy)
}

@Serializable
enum class RegionLabel { BUBBLE, TEXT_BUBBLE, TEXT_FREE }

@Serializable
data class Detection(
    val label: RegionLabel,
    val score: Float,
    val box: Box,
    val touchesEdge: Boolean,
) {
    /** Below the confident threshold: kept only if OCR finds real text in it. */
    val weak get() = score < TextDetectorThresholds.CONFIDENT
}

object TextDetectorThresholds {
    const val MIN = 0.28f
    const val CONFIDENT = 0.45f
}

@Serializable
enum class BlockKind { BUBBLE, FREE }

@Serializable
data class TextBlock(
    val id: Int,
    val kind: BlockKind,
    val box: Box,
    val bubble: Box?,
    val page: Int,
    val text: String,
    val confidence: Float,
    /** OCR could not read it: the crop is sent to the model to read and translate. */
    val vision: Boolean = false,
)

@Serializable
data class TranslatedLine(
    val id: Int,
    val en: String,
    val it: String,
    val type: String,
)
