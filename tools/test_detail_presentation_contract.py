from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/zycomic/app"


class DetailPresentationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.picacg = (JAVA / "ui/picacg/PicacgDetailOverlay.kt").read_text()
        cls.manga = (JAVA / "ui/manga/MangaDetailScreen.kt").read_text()
        cls.presentation = (JAVA / "ui/manga/MangaDetailPresentation.kt").read_text()

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
            "PicacgRepository.toggleFavourite(selectedId)",
            "PicacgRepository.toggleLike(selectedId)",
            "PicacgRepository.recommendations(selectedId)",
            "PicacgCommentsDialog(selectedId",
            "SearchOverlay(",
            "PicacgDetailSearchPolicy.SEARCH_PROVIDER",
            "PicacgImageLoader.get(context)",
            "ReaderLauncher.launch(context, detail, chapterId, sourceId = PicacgSource.id)",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, self.picacg)

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


if __name__ == "__main__":
    unittest.main()
