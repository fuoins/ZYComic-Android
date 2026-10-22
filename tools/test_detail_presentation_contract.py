from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/zycomic/app"


class DetailPresentationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.picacg = (JAVA / "ui/picacg/PicacgDetailOverlay.kt").read_text()
        cls.manga = (JAVA / "ui/manga/MangaDetailScreen.kt").read_text()
        cls.presentation = (JAVA / "ui/manga/MangaDetailPresentation.kt").read_text()
        cls.backdrop = (JAVA / "ui/manga/MangaDetailBackdrop.kt").read_text()
        cls.cache = (JAVA / "picacg/PicacgDetailCache.kt").read_text()
        cls.auth = (JAVA / "picacg/PicacgAuthStore.kt").read_text()

    def test_manwa_jm_and_picacg_use_the_same_presentation_components(self):
        for component in (
            "MangaDetailDescription",
            "MangaDetailChapterGrid",
        ):
            with self.subTest(component=component):
                self.assertIn(f"{component}(", self.manga)
                self.assertIn(f"{component}(", self.picacg)
                self.assertIn(f"fun {component}(", self.presentation)

        self.assertIn("MangaDetailTagChips(", self.manga)
        self.assertIn("MangaDetailTagChips(", self.picacg)
        self.assertIn("trailingContent: @Composable () -> Unit = {}", self.presentation)
        self.assertIn("trailingContent()", self.presentation)
        self.assertIn("MangaDetailRecommendationCard(", self.manga)
        self.assertIn("MangaDetailRecommendationRow(", self.picacg)
        self.assertIn("fun MangaDetailRecommendationCard(", self.presentation)
        self.assertIn("fun MangaDetailRecommendationRow(", self.presentation)

    def test_picacg_interactions_stay_on_its_existing_routes_and_api(self):
        for contract in (
            "PicacgRepository.detail(selectedId)",
            "PicacgRepository.comicState(selectedId)",
            "PicacgRepository.toggleFavourite(requestId)",
            "PicacgRepository.toggleLike(requestId)",
            "PicacgRepository.recommendations(selectedId)",
            "PicacgCommentsDialog(selectedId",
            "SearchOverlay(",
            "PicacgDetailSearchPolicy.SEARCH_PROVIDER",
            "PicacgImageLoader.get(context)",
            "ReaderLauncher.launch(context, detail, target.chapterId, page = target.page, sourceId = PicacgSource.id)",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, self.picacg)

    def test_recommendations_are_independent_lightweight_and_session_scoped(self):
        repository = (JAVA / "picacg/PicacgRepository.kt").read_text()
        recommendations = repository.split("suspend fun recommendations(id: String)", 1)[1].split("/** Toggle POST", 1)[0]
        self.assertIn("recommendationApi.recommendationIds(id)", recommendations)
        self.assertIn("loadPicacgRecommendations(ids)", recommendations)
        self.assertIn("recommendationMetadata(recommendedId, condition.sessionRevision)", recommendations)
        self.assertEqual(2, recommendations.count("browseConditionIsCurrent(condition)"))
        self.assertIn("filterDiscovery(cards, condition.blockedKeywords)", recommendations)
        self.assertIn("loadComicJson(id, expectedSessionRevision)", recommendations)
        self.assertIn('remove("chapterList")', recommendations)
        self.assertNotIn("allEpisodes(", recommendations)
        self.assertNotIn("= detail(", recommendations)
        self.assertNotIn('/recommendation"', repository)
        detail_effect, related_effect = self.picacg.split("// Recommendations may fail independently:", 1)
        self.assertNotIn("PicacgRepository.recommendations(", detail_effect)
        self.assertIn("LaunchedEffect(selectedId, session, loggedIn, refresh, blockKeywords)", related_effect)
        self.assertIn("relatedLoading = false", related_effect)
        self.assertIn("KMR.strings.picacg_recommendations_empty", related_effect)
        self.assertIn("relatedError = recommendationFailure", related_effect)

    def test_actions_appear_above_synopsis_and_tags(self):
        description = self.picacg.index("MangaDetailDescription(detail.text)")
        tags = self.picacg.index("MangaDetailTagChips(")
        for action in ("changeInteraction(true)", "changeInteraction(false)", "comments = true"):
            self.assertLess(self.picacg.index(action), description)
            self.assertLess(self.picacg.index(action), tags)

    def test_title_reuses_three_line_expansion_without_losing_search(self):
        self.assertIn("rememberSaveable(manga.id)", self.presentation)
        self.assertIn("maxLines = detailTitleMaxLines(titleExpanded)", self.presentation)
        self.assertIn("KMR.strings.detail_title_collapse else KMR.strings.detail_title_expand", self.presentation)
        self.assertIn("onSearchTitle(manga.name)", self.presentation)

    def test_reading_fab_matches_existing_placement_and_keeps_bottom_content_clear(self):
        for contract in ("FloatingActionButton(", "Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)",
                         "Icons.Default.PlayArrow", "if (!busy) openChapter(readableDetail)", "PaddingValues(bottom = 88.dp)",
                         "picacgDetailReadingTarget(detail)", "page = target.page"):
            self.assertIn(contract, self.picacg)
        resume = (JAVA / "ui/picacg/PicacgDetailReadingTarget.kt").read_text()
        for contract in ('getMangaByUrlAndSourceId("/comic/${detail.id}", PicacgSource.id)',
                         "getHistoryByMangaId(localManga.id)", "PicacgChapterProgressPolicy.chapterUrl(detail.id, it.sort)",
                         "it.url in available", "recent.lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong())"):
            self.assertIn(contract, resume)

    def test_all_platforms_reuse_the_backdrop_without_sharing_image_loaders(self):
        self.assertIn("MangaDetailBackdrop(", self.manga)
        self.assertIn("MangaDetailBackdrop(", self.picacg)
        for contract in (".blur(7.dp)", ".alpha(0.2f)", "Brush.verticalGradient(", "0.5f to Color.Transparent"):
            self.assertIn(contract, self.backdrop)
        self.assertIn("imageLoader = jmImageLoader", self.manga)
        self.assertIn("imageLoader = PicacgImageLoader.get(context)", self.picacg)

    def test_detail_toolbar_keeps_back_arrow_and_recommendation_scroll_not_profile_refresh(self):
        self.assertIn("Icons.AutoMirrored.Filled.ArrowBack", self.picacg)
        self.assertIn("listState.animateScrollToItem(2)", self.picacg)
        self.assertIn('item(key = "recommendations")', self.picacg)
        self.assertNotIn("picacg_profile_refresh", self.picacg)

    def test_first_load_is_centered_in_the_content_area(self):
        content = self.picacg.split("contentAlignment = Alignment.Center) {", 1)[1]
        self.assertIn("if (detail == null && error == null) CircularProgressIndicator()", content)
        self.assertIn("LazyColumn(Modifier.fillMaxSize(), state = listState", content)

    def test_snapshot_is_read_before_network_refresh_and_kept_process_only(self):
        self.assertLess(self.picacg.index("PicacgDetailCache.get(session, selectedId)"), self.picacg.index("PicacgRepository.detail(selectedId)"))
        snapshot_initializer = self.picacg.split("val snapshot = remember(", 1)[1].split("var manga", 1)[0]
        self.assertNotIn("activateSession", snapshot_initializer)
        self.assertLess(self.picacg.index("PicacgDetailCache.activateSession(session, loggedIn)"), self.picacg.index("PicacgRepository.detail(selectedId)"))
        self.assertIn("mutableStateOf(snapshot?.manga)", self.picacg)
        self.assertIn("mutableStateOf(snapshot?.state)", self.picacg)
        self.assertIn("PicacgDetailCache.put(session, selectedId, response, metadata)", self.picacg)
        for forbidden in ("SharedPreferences", "java.io", "File(", "token", "Jm", "Manwa"):
            self.assertNotIn(forbidden, self.cache)

    def test_snapshot_is_bounded_and_rejects_old_sessions(self):
        self.assertIn("MAX_ENTRIES = 20", self.cache)
        self.assertIn("LinkedHashMap<String, PicacgDetailSnapshot>(MAX_ENTRIES, 0.75f, true)", self.cache)
        self.assertIn("if (activeSession != revision) return false", self.cache)
        self.assertIn("if (activeSession != next)", self.cache)
        self.assertIn("entries.clear()", self.cache)
        self.assertIn("while (entries.size > MAX_ENTRIES)", self.cache)
        self.assertEqual(2, self.auth.count("PicacgDetailCache.clear()"))

    def test_interactions_invalidate_then_refresh_authoritative_snapshot_state(self):
        self.assertIn("if (busy || refreshing || state == null", self.picacg)
        self.assertIn("val requestId = selectedId", self.picacg)
        self.assertIn("PicacgDetailCache.invalidateState(session, requestId)", self.picacg)
        self.assertIn("PicacgRepository.comicState(requestId)", self.picacg)
        self.assertIn("PicacgDetailCache.put(session, requestId, it, result)", self.picacg)
        comments_close = self.picacg.split("PicacgCommentsDialog(selectedId", 1)[1]
        self.assertIn("PicacgDetailCache.invalidateState(session, selectedId)", comments_close)
        self.assertIn("refresh++", comments_close)

    def test_refresh_failure_keeps_content_but_invalidates_stale_toggle_flags(self):
        failure = self.picacg.split("catch (failure: Exception)", 1)[1].split("finally", 1)[0]
        self.assertIn("state = null", failure)
        self.assertIn("PicacgDetailCache.invalidateState(session, selectedId)", failure)
        self.assertNotIn("manga = null", failure)
        self.assertIn("enabled = metadata != null && !busy && !refreshing", self.picacg)

    def test_manga_specific_account_actions_remain_in_manga_screen(self):
        for contract in (
            "JmDetailActions(",
            "vm.toggleFavorite()",
            "vm.ensureAccountLogin()",
            "JmTagGroup(",
            "vm.toggleChapterSort()",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, self.manga)


class PicacgApprovedPolishContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.toolbar = (JAVA / "ui/components/LibraryHistoryToolbar.kt").read_text()
        cls.history = (JAVA / "ui/components/PicacgLocalHistoryScreen.kt").read_text()
        cls.library = (JAVA / "ui/components/PicacgLibraryScreen.kt").read_text()
        cls.browse = (JAVA / "ui/picacg/PicacgBrowseScreen.kt").read_text()
        cls.storage = (JAVA / "ui/settings/DataStorageScreen.kt").read_text()
        cls.loader = (JAVA / "ui/components/PicacgMangaCard.kt").read_text()
        cls.config = (JAVA / "net/DevConfig.kt").read_text()

    def test_history_selection_remains_present_with_real_disabled_semantics(self):
        self.assertIn("selectionSupported = true,", self.history)
        self.assertIn("selectionEnabled = allRows != null && rows.isNotEmpty(),", self.history)
        self.assertIn("selectionEnabled: Boolean = true,", self.toolbar)
        self.assertIn("enabled = selectionEnabled,", self.toolbar)
        self.assertIn("IconButton(onClick = onClick, enabled = enabled", self.toolbar)

    def test_favorites_refresh_remains_visible_even_when_logged_out_or_busy(self):
        for contract in ("refreshSupported = !history,", "refreshEnabled = loggedIn,", "refreshBusy = loading || removingSelected,", "refreshIndicatorSeparate = true,"):
            self.assertIn(contract, self.library)
        self.assertIn("enabled = refreshEnabled && !refreshBusy", self.toolbar)
        self.assertIn("if (busy && !busyIndicatorSeparate)", self.toolbar)
        independent = self.toolbar.split("Box(contentAlignment = Alignment.Center)", 1)[1]
        self.assertLess(independent.index("Icon(imageVector = icon"), independent.index("if (busy)"))
        self.assertIn("refreshIndicatorSeparate: Boolean = false,", self.toolbar)

    def test_ranking_tabs_are_outside_authentication_and_loading_branches(self):
        tabs = self.browse.index("if (pageMode == PicacgBrowsePageMode.RANKING)")
        login = self.browse.index("if (!loggedIn) {\n            Text(stringResource(KMR.strings.picacg_session_required))")
        loading = self.browse.index("if (loading && mangas.isEmpty() && !hasSnapshot)")
        self.assertLess(tabs, login)
        self.assertLess(tabs, loading)
        self.assertEqual(1, self.browse.count("LazyRow("))

    def test_browse_query_identity_controls_cached_first_frame_and_empty_snapshot_presentation(self):
        identity = self.browse.split("val queryIdentity = ", 1)[1].split("\n", 1)[0]
        for dimension in ("session", "loggedIn", "tabId", "initialFilter", "pageMode", "mode", "currentFilter", "condition", "routeState.revision", "keywordRevision", "blockKeywords"):
            with self.subTest(dimension=dimension):
                self.assertIn(dimension, identity)
        self.assertLess(self.browse.index("val initialSnapshot = remember(queryIdentity)"), self.browse.index("LaunchedEffect(queryIdentity, page, refresh, browseCacheRevision)"))
        self.assertIn("var pageSnapshots by remember(queryIdentity) { mutableStateOf(mapOf(1 to initialSnapshot?.items.orEmpty())) }", self.browse)
        self.assertIn("var hasSnapshot by remember(queryIdentity) { mutableStateOf(initialSnapshot != null) }", self.browse)
        self.assertIn("if (loading && mangas.isEmpty() && !hasSnapshot)", self.browse)
        self.assertGreaterEqual(self.browse.count("if ((!loading || hasSnapshot) && error == null && mangas.isEmpty())"), 2)

    def test_ranking_pager_preview_exists_before_selected_data_page(self):
        host = (JAVA / "ui/browse/BrowseScreen.kt").read_text()
        page = host.split("if (tabPlatform == ContentPlatform.PICACG)", 1)[1].split("if (item?.kind == TabKind.SPECIAL", 1)[0]
        selected, preview = page.split("} else if (pageMode == com.zycomic.app.ui.picacg.PicacgBrowsePageMode.RANKING)", 1)
        self.assertIn("isActive = page == mainTab,", host)
        self.assertIn("if (isActive) {\n            com.zycomic.app.ui.picacg.PicacgBrowseScreen(", selected)
        self.assertNotIn("vm.mainTab.value", page)
        self.assertIn("pageMode = pageMode,", selected)
        self.assertIn("com.zycomic.app.ui.picacg.PicacgRankingChips()", preview)
        self.assertIn("padding(horizontal = 12.dp, vertical = 8.dp)", preview)
        for forbidden in ("PicacgBrowseScreen(", "Repository", "LaunchedEffect", "selectMainTab"):
            self.assertNotIn(forbidden, preview)

    def test_static_ranking_controls_keep_all_five_modes_order_and_default(self):
        row = self.browse.split("internal fun PicacgRankingChips(", 1)[1].split("fun PicacgBrowseScreen(", 1)[0]
        modes = row.split("items = listOf(", 1)[1].split("),", 1)[0]
        self.assertEqual(
            ["LEADERBOARD_24H", "LEADERBOARD_7D", "LEADERBOARD_30D", "RANDOM", "LATEST"],
            re.findall(r"PicacgFeedMode\.(\w+)", modes),
        )
        for contract in ("selectedMode: PicacgFeedMode = PicacgFeedMode.LEADERBOARD_24H", "onSelectMode: (PicacgFeedMode) -> Unit = {}", "CompactBrowseFilterChip(", '"日榜"', '"周榜"', '"月榜"', "KMR.strings.picacg_random", "KMR.strings.picacg_latest"):
            self.assertIn(contract, row)
        for mapping in ('LEADERBOARD_24H("H24")', 'LEADERBOARD_7D("D7")', 'LEADERBOARD_30D("D30")'):
            self.assertIn(mapping, self.browse)
        for forbidden in ("Repository", "LaunchedEffect", "collectAsState"):
            self.assertNotIn(forbidden, row)
        active = self.browse.split("if (pageMode == PicacgBrowsePageMode.RANKING)", 1)[1].split("if (!loggedIn)", 1)[0]
        self.assertIn("PicacgRankingChips(selectedMode = mode)", active)
        self.assertIn('selectMode(item, if (item == PicacgFeedMode.LATEST) PicacgBrowseFilter(sort = "dd") else filter)', active)

    def test_pager_selection_still_waits_for_settle_without_prefetch(self):
        host = (JAVA / "ui/browse/BrowseScreen.kt").read_text()
        self.assertIn("snapshotFlow { pagerState.settledPage }", host)
        self.assertIn("pagerState.animateScrollToPage(index)\n                                            vm.selectMainTab(index)", host)
        self.assertEqual(1, host.count("com.zycomic.app.ui.picacg.PicacgBrowseScreen("))
        self.assertEqual(1, self.browse.count("LaunchedEffect(queryIdentity, page, refresh, browseCacheRevision)"))

    def test_category_and_custom_filter_pages_do_not_get_ranking_preview(self):
        host = (JAVA / "ui/browse/BrowseScreen.kt").read_text()
        page = host.split("if (tabPlatform == ContentPlatform.PICACG)", 1)[1].split("if (item?.kind == TabKind.SPECIAL", 1)[0]
        self.assertIn("BrowsePageType.PICACG_CATEGORY -> com.zycomic.app.ui.picacg.PicacgBrowsePageMode.CATEGORY", page)
        self.assertIn("item?.kind == TabKind.FILTER && item.id != BrowseTabStore.ID_PICACG_HOME", page)
        preview = page.split("} else if (pageMode == com.zycomic.app.ui.picacg.PicacgBrowsePageMode.RANKING)", 1)[1]
        self.assertIn("} else {\n            Box(Modifier.fillMaxSize())", preview)
        self.assertEqual(1, preview.count("PicacgRankingChips()"))

    def test_picacg_disk_cache_is_counted_and_statistics_refresh_after_clear(self):
        for contract in ("PicacgImageLoader.diskCacheDirectory(context)", "coilBytes + picacgBytes", "KMR.strings.picacg_image_cache_size", "cacheRevision++", "remember(cacheRevision) { imageDiskCacheSize() }"):
            self.assertIn(contract, self.storage)

    def test_image_cache_cleanup_uses_separate_loaders_and_directories(self):
        self.assertIn('cacheDir.resolve("picacg_image_cache")', self.loader)
        self.assertIn(".directory(diskCacheDirectory(context))", self.loader)
        cleanup = self.config.split("fun clearImageCaches()", 1)[1].split("fun clearImageMemoryCache()", 1)[0]
        self.assertIn("SingletonImageLoader.get(ctx)", cleanup)
        self.assertIn("PicacgImageLoader.clearCache(ctx)", cleanup)
        self.assertNotIn("JmCoverImageLoader", cleanup)
        self.assertNotIn("jm_cover_cache", cleanup)


if __name__ == "__main__":
    unittest.main()
