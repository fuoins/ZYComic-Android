package eu.kanade.tachiyomi.ui.reader.model

sealed class ChapterTransition : ReaderItem {

    abstract val from: ReaderChapter
    abstract val to: ReaderChapter?

    class Prev(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    class Next(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterTransition) return false
        return javaClass == other.javaClass && from == other.from && to == other.to
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        result = 31 * result + javaClass.hashCode()
        return result
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(from=${from.chapter.url}, to=${to?.chapter?.url})"
    }
}
