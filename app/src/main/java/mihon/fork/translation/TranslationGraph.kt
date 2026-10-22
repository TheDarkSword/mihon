package mihon.fork.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga

@ContributesTo(AppScope::class)
interface TranslationGraph {
    fun inject(worker: TranslationWorker)

    val translationManager: TranslationManager
    val translationPreferences: TranslationPreferences
    val translationStorage: TranslationStorage
    val getFavorites: GetFavorites
    val getChaptersByMangaId: GetChaptersByMangaId
    val getManga: GetManga
}
