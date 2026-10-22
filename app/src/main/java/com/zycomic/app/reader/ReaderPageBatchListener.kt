package com.zycomic.app.reader

import eu.kanade.tachiyomi.source.model.Page
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Reader-only opt-in. Downloads and other source consumers retain the full-list contract. */
class ReaderPageBatchListener(
    val onPages: suspend (pages: List<Page>, complete: Boolean) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReaderPageBatchListener>
}
