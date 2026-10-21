package com.zycomic.app.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A coroutine-scoped listener for directory batches; preloads cannot leak into another reader load. */
class PicacgPageListProgress(
    val onBatch: (batch: Int, fetchedImages: Int, hasMore: Boolean) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PicacgPageListProgress>
}

/** Reports progress only when this load explicitly opted into directory feedback. */
suspend fun reportPicacgPageListProgress(batch: Int, fetchedImages: Int, hasMore: Boolean) {
    currentCoroutineContext()[PicacgPageListProgress]?.onBatch?.invoke(batch, fetchedImages, hasMore)
}
