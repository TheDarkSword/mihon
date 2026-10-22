package mihon.translation.render

import kotlinx.serialization.Serializable

/** How the Italian text is lettered. Changing it only needs a re-render, not a new translation. */
@Serializable
data class TextStyle(
    val uppercase: Boolean = true,
    /** Multiplier on the automatically fitted size. */
    val scale: Float = 1f,
    val font: String = COMIC_NEUE,
    /** Multiplier on the stroke that thickens the letters. */
    val weight: Float = 1f,
) {
    companion object {
        const val COMIC_NEUE = "comic_neue"
        const val BANGERS = "bangers"
        const val PATRICK_HAND = "patrick_hand"
        const val KALAM = "kalam"

        val FONTS = listOf(COMIC_NEUE, BANGERS, PATRICK_HAND, KALAM)

        fun fontFile(font: String) = when (font) {
            BANGERS -> "Bangers-Regular.ttf"
            PATRICK_HAND -> "PatrickHand-Regular.ttf"
            KALAM -> "Kalam-Bold.ttf"
            else -> "ComicNeue-Bold.ttf"
        }
    }
}
