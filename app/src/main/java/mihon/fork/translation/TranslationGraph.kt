package mihon.fork.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
interface TranslationGraph {
    fun inject(worker: TranslationWorker)

    val translationManager: TranslationManager
    val translationPreferences: TranslationPreferences
    val translationStorage: TranslationStorage
}
