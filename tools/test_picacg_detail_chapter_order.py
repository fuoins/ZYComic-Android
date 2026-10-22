from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/zycomic/app"


class PicacgDetailChapterOrderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.overlay = (JAVA / "ui/picacg/PicacgDetailOverlay.kt").read_text()

    def test_default_is_descending_and_control_toggles_the_display_order(self):
        self.assertIn("var chapterAscending by remember(selectedId) { mutableStateOf(false) }", self.overlay)
        self.assertIn("chapterAscending = !chapterAscending", self.overlay)
        self.assertIn('if (chapterAscending) "升序" else "降序"', self.overlay)
        self.assertIn("Icons.Filled.SwapVert", self.overlay)
        self.assertIn("import androidx.compose.foundation.layout.width", self.overlay)

    def test_only_grid_receives_a_sorted_copy_and_chapter_click_keeps_wire_id(self):
        grid = self.overlay.split("MangaDetailChapterGrid(", 1)[1].split("onChapterClick", 1)[0]
        self.assertRegex(grid, re.compile(
            r"chapters = if \(chapterAscending\) \{\s*"
            r"detail\.chapterList\.sortedBy \{ it\.sort \}\s*"
            r"\} else \{\s*detail\.chapterList\.sortedByDescending \{ it\.sort \}",
            re.DOTALL,
        ))
        self.assertIn("onChapterClick = { chapter -> openChapter(detail, chapter.id) }", self.overlay)
        self.assertNotRegex(self.overlay, r"chapterList\.(?:sortBy|sortByDescending|reverse|sort)\s*\(")
        self.assertNotRegex(self.overlay, r"copy\(\s*chapterList\s*=")

    def test_resume_and_reader_launch_keep_original_detail_not_display_order(self):
        launch = self.overlay.split("fun openChapter(", 1)[1].split("fun changeInteraction(", 1)[0]
        self.assertIn("picacgDetailReadingTarget(detail)", launch)
        self.assertIn("ReaderLauncher.launch(context, detail, target.chapterId", launch)
        self.assertNotIn("chapterAscending", launch)
        target = (JAVA / "ui/picacg/PicacgDetailReadingTarget.kt").read_text()
        self.assertIn("detail.chapterList.minByOrNull { it.sort }", target)
        self.assertIn("available.getValue(recent.url).id", target)
        self.assertNotIn("chapterAscending", target)


if __name__ == "__main__":
    unittest.main()
