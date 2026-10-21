package mihon.fork.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(preferenceStore: PreferenceStore) {

    val deleteOriginal = preferenceStore.getBoolean("translation_delete_original", true)

    /** Review with the full Flash models while their small daily quota lasts. */
    val qualityReview = preferenceStore.getBoolean("translation_quality_review", false)

    /** Manga ids for which the reader shows the original chapters. */
    val originalManga = preferenceStore.getStringSet("translation_original_manga", emptySet())

    /** Manga ids whose new chapters are queued as soon as a library update finds them. */
    val autoManga = preferenceStore.getStringSet("translation_auto_manga", emptySet())

    /** Day on which the user was told the quality reviewers ran out, to say it once a day. */
    val qualityFallbackNotifiedDay = preferenceStore.getString("translation_quality_fallback_day", "")

    val requestsDay = preferenceStore.getString("translation_requests_day", "")

    val requestsCount = preferenceStore.getInt("translation_requests_count", 0)

    /** Epoch millis until which the queue waits for the daily quota to reset. */
    val pausedUntil = preferenceStore.getLong("translation_paused_until", 0)
}
