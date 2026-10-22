from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"


class ReaderDetailNavigationTest(unittest.TestCase):
    def test_title_opens_registered_activity_with_current_remote_identity(self):
        reader = (JAVA / "eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name="com.zycomic.app.ui.MainActivity"', manifest)
        self.assertIn("import com.zycomic.app.ui.MainActivity", reader)
        self.assertNotIn("import eu.kanade.tachiyomi.ui.main.MainActivity", reader)
        self.assertIn("onClickTopAppBar = ::openMangaScreen", reader)
        callback = reader.split("private fun openMangaScreen()", 1)[1].split("private fun openChapterInWebView()", 1)[0]
        self.assertIn("MainActivity.newReaderDetailIntent(this, manga.source, manga.url)", callback)
        self.assertIn("startActivity(detailIntent)", callback)
        self.assertIn("finish()", callback)
        self.assertNotIn("manga.id", callback)
        self.assertNotIn("SHORTCUT_MANGA", callback)

    def test_current_activity_consumes_detail_route_on_cold_and_warm_start(self):
        main = (JAVA / "com/zycomic/app/ui/MainActivity.kt").read_text()
        create = main.split("override fun onCreate(", 1)[1].split("override fun onNewIntent(", 1)[0]
        warm = main.split("override fun onNewIntent(", 1)[1].split("@Composable", 1)[0]
        self.assertIn("intent?.readerDetailBookId()", create)
        self.assertIn("intent.readerDetailBookId()", warm)
        self.assertIn("setIntent(intent)", warm)
        self.assertIn("Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP", main)
        self.assertIn("putExtra(ReaderDetailRoute.EXTRA_BOOK_ID, bookId)", main)
        route = main.split("LaunchedEffect(readerDetailBookId)", 1)[1].split("var loginPlatform", 1)[0]
        self.assertIn("detailStack.lastOrNull() != bookId", route)
        self.assertIn("openDetail(bookId)", route)
        self.assertIn("showSearch = false", route)
        self.assertIn("onReaderDetailConsumed()", route)
        self.assertIn("intent?.removeExtra(ReaderDetailRoute.EXTRA_BOOK_ID)", main)


if __name__ == "__main__":
    unittest.main()
