#!/usr/bin/env python3
"""Source contracts for the Picacg incremental reader directory path.

Run with: python3 tools/test_reader_incremental_directory.py
These are deliberately source-level contracts: the Android reader wiring is not
pulled into the isolated Kotlin policy test runner.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def source(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def between(text: str, start: str, end: str) -> str:
    start_at = text.index(start)
    end_at = text.index(end, start_at + len(start))
    return text[start_at:end_at]


REPOSITORY = "app/src/main/java/com/zycomic/app/picacg/PicacgRepository.kt"
SOURCE = "app/src/main/java/com/zycomic/app/reader/PicacgSource.kt"
CHAPTER_LOADER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt"
HTTP_LOADER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt"
VIEW_MODEL = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
CHAPTER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt"
PAGE_LOADER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/PageLoader.kt"
PAGER_ADAPTER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt"
WEBTOON_ADAPTER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt"
PAGER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt"
WEBTOON = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt"
ACTIVITY = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"


class ReaderIncrementalDirectorySourceContracts(unittest.TestCase):
    def test_reader_fresh_bypasses_only_the_page_manifest_cache(self):
        repository = source(REPOSITORY)
        pages = between(repository, "suspend fun pages(", "suspend fun allPages(")
        self.assertIn("readerFresh: Boolean = false", pages)
        self.assertIn("if (!readerFresh) pageCache.get(cacheKey)", pages)
        self.assertIn("pageCache.put(cacheKey, response)", pages)
        # Fresh reader calls still fetch and refresh the cache rather than returning a stale list.
        bypass = pages.index("if (!readerFresh) pageCache.get(cacheKey)")
        network_fetch = pages.index("client().request(", bypass)
        cache_refresh = pages.index("pageCache.put(cacheKey, response)", network_fetch)
        self.assertLess(bypass, network_fetch)
        self.assertLess(network_fetch, cache_refresh)

    def test_source_streams_accumulated_pages_and_marks_only_real_end_complete(self):
        picacg_source = source(SOURCE)
        page_list = between(picacg_source, "override suspend fun getPageList(", "override suspend fun getImageUrl(")
        self.assertIn("currentCoroutineContext()[ReaderPageBatchListener]", page_list)
        self.assertIn("readerFresh = readerBatches != null", page_list)
        self.assertIn("images += response.items", page_list)
        self.assertIn("images.mapIndexed", page_list)
        self.assertIn("!response.hasMore", page_list)
        self.assertIn("while (page <= 1000)", page_list)
        self.assertIn("if (readerBatches != null && !directoryComplete)", page_list)
        self.assertIn('throw IOException("Picacg reader directory is incomplete', page_list)

    def test_http_loader_keeps_fallback_retry_and_applies_freshness_to_reader_batches(self):
        loader = source(HTTP_LOADER)
        ordinary_load = between(loader, "override suspend fun getPages():", "override suspend fun getPages(onPages:")
        self.assertIn("source.getPageList(chapter.chapter)", ordinary_load)
        catch_at = ordinary_load.index("catch (e: Throwable)")
        cancellation_rethrow = ordinary_load.index("if (e is CancellationException) throw e", catch_at)
        fallback_call = ordinary_load.index("source.getPageList(chapter.chapter)", cancellation_rethrow)
        self.assertLess(catch_at, cancellation_rethrow)
        self.assertLess(cancellation_rethrow, fallback_call)
        batched = between(loader, "override suspend fun getPages(onPages:", "override suspend fun loadPage(")
        self.assertIn("if (source.id != PicacgSource.id) return getPages()", batched)
        self.assertIn("ReaderPageSnapshots<ReaderPage>", batched)
        self.assertIn("ReaderPageBatchListener", batched)
        self.assertIn("onPages(latest, complete)", batched)
        self.assertIn(") { getPages() }", batched)

    def test_chapter_loader_publishes_nonempty_partial_batches_but_retains_final_completion(self):
        loader = source(CHAPTER_LOADER)
        load = between(loader, "suspend fun loadChapter(", "private fun chapterIsReady(")
        self.assertIn("suspend fun publish(pages:", load)
        self.assertIn("loader.setChapterPages(pages, complete)", load)
        self.assertIn("if (pages.isNotEmpty()) chapter.state = ReaderChapter.State.Loaded(pages, complete)", load)
        self.assertIn("if (onPagesReady != null) publish(partial, complete)", load)
        self.assertIn("if (!receivedBatch || onPagesReady == null) publish(pages, finalComplete)", load)
        self.assertIn("if (pages.isEmpty()) throw", load)
        publish = between(load, "suspend fun publish(pages:", "val pages = loader.getPages")
        self.assertIn("withUIContext", publish)

    def test_vm_publishes_resolvable_prefixes_without_promoting_them_to_complete(self):
        vm = source(VIEW_MODEL)
        load = between(vm, "private suspend fun loadChapter(", "fun markInitialPicacgDirectoryFailed(")
        self.assertIn("suspend fun publishPages()", load)
        self.assertIn("val loaded = chapter.state as? ReaderChapter.State.Loaded ?: return", load)
        self.assertIn("ReaderDirectoryPolicy.resolveTarget(chapter.requestedPage, loaded.pages.size, loaded.directoryComplete) == null", load)
        self.assertIn("directoryRevision = it.directoryRevision + 1L", load)
        self.assertIn("::publishPages", load)
        chapter = source(CHAPTER)
        self.assertIn("val directoryComplete: Boolean", chapter)
        self.assertIn("(state as? State.Loaded)?.directoryComplete == true", chapter)
        self.assertIn("var pendingPage: Int? = null", chapter)
        vm_tail = source(VIEW_MODEL)
        self.assertIn("ReaderDirectoryPolicy.totalPages(loaded.pages.size, loaded.directoryComplete)", vm_tail)
        self.assertIn("if (readerChapter.directoryComplete && (", vm_tail)
        self.assertIn("readerChapter.pages?.lastIndex == pageIndex", vm_tail)

    def test_adapters_do_not_append_transitions_or_next_chapter_pages_on_a_prefix(self):
        pager_adapter = source(PAGER_ADAPTER)
        pager_setup = between(pager_adapter, "fun setChapters(", "override fun getCount(")
        self.assertIn("if (chapters.currChapter.directoryComplete)", pager_setup)
        self.assertIn("nextTransition = if (chapters.currChapter.directoryComplete)", pager_setup)
        self.assertIn("chapters.nextChapter?.pages?.let(newItems::addAll)", pager_setup)
        self.assertIn("chapters.prevChapter?.takeIf { it.directoryComplete }", pager_setup)

        webtoon_adapter = source(WEBTOON_ADAPTER)
        webtoon_setup = between(webtoon_adapter, "fun setChapters(", "private fun updateItems(")
        self.assertIn("chapters.currChapter.directoryComplete", webtoon_setup)
        self.assertIn("chapters.nextChapter?.pages?.let(newItems::addAll)", webtoon_setup)

    def test_both_viewers_gate_tail_preload_and_defer_unavailable_resume_targets(self):
        pager = source(PAGER)
        pager_preload = between(pager, "private fun onReaderPageSelected(", "private fun onTransitionSelected(")
        self.assertIn("page.chapter.directoryComplete && pages.size - page.number < 5", pager_preload)
        pager_set = between(pager, "internal fun setChaptersInternal(", "override fun moveToPage(")
        self.assertIn("val pending = chapter.pendingPage", pager_set)
        self.assertIn("pending ?: chapter.requestedPage", pager_set)
        self.assertIn("if (pager.isGone || pending != null)", pager_set)
        self.assertIn("chapter.directoryComplete", pager_set)
        self.assertIn("if (target != null)", pager_set)
        self.assertIn("chapter.pendingPage = null", pager_set)

        webtoon = source(WEBTOON)
        webtoon_preload = between(webtoon, "private fun onPageSelected(", "private fun onTransitionSelected(")
        self.assertIn("page.chapter.directoryComplete && pages.size - page.number < 5", webtoon_preload)
        webtoon_set = between(webtoon, "override fun setChapters(", "fun onAdapterItemsUpdated(")
        self.assertIn("val pending = chapter.pendingPage", webtoon_set)
        self.assertIn("pending ?: chapter.requestedPage", webtoon_set)
        self.assertIn("if (recycler.isGone || pending != null)", webtoon_set)
        self.assertIn("if (target != null)", webtoon_set)
        self.assertIn("chapter.pendingPage = null", webtoon_set)

    def test_retry_preserves_requested_resume_page_and_does_not_drop_existing_failure_retry(self):
        vm = source(VIEW_MODEL)
        retry = between(vm, "fun retryInitialPicacgDirectory(", "private fun activateChapterForReading(")
        self.assertIn("val target = failedDirectoryPage ?: initialPageToOpen", retry)
        self.assertIn("chapter.pendingPage = target", retry)
        self.assertIn("loadChapter(currentLoader, chapter, target, reportPicacgProgress = true)", retry)

        loader = source(HTTP_LOADER)
        page_retry = between(loader, "override fun retryPage(", "override fun retryAfterRuntimeRecovery(")
        self.assertIn("page.manualRetryCount", page_retry)
        self.assertIn("page.status = Page.State.Queue", page_retry)
        self.assertIn("enqueue(page", page_retry)
        source_text = source(SOURCE)
        image_fetch = between(source_text, "override suspend fun getImage(page:", "private fun toSManga(")
        self.assertIn("val call = client.newCall(request)", image_fetch)
        self.assertIn("call.awaitSuccess()", image_fetch)

    def test_adjacent_navigation_uses_a_one_time_page_zero_target_not_a_late_activity_jump(self):
        vm = source(VIEW_MODEL)
        adjacent = between(vm, "private suspend fun loadAdjacent(", "suspend fun preload(")
        self.assertIn("chapter.pendingPage = 0", adjacent)
        self.assertIn("chapter.requestedPage = 0", adjacent)
        self.assertIn("loadChapter(loader, chapter", adjacent)
        activity = source(ACTIVITY)
        next_action = between(activity, "private fun loadNextChapter()", "private fun loadPreviousChapter()")
        previous_action = between(activity, "private fun loadPreviousChapter()", "fun onPageSelected(")
        self.assertIn("viewModel.loadNextChapter()", next_action)
        self.assertIn("viewModel.loadPreviousChapter()", previous_action)
        self.assertNotIn("moveToPageIndex(0)", next_action + previous_action)
        self.assertNotIn("moveToPageIndex(0)", activity)


if __name__ == "__main__":
    unittest.main(verbosity=2)
