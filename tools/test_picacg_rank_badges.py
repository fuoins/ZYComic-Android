from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/zycomic/app"


def _skip_quoted_or_commented(source, index):
    """Return the first character after a Kotlin string/comment beginning at index."""
    if source.startswith("//", index):
        end = source.find("\n", index + 2)
        return len(source) if end == -1 else end + 1
    if source.startswith("/*", index):
        depth = 1
        cursor = index + 2
        while cursor < len(source) and depth:
            if source.startswith("/*", cursor):
                depth += 1
                cursor += 2
            elif source.startswith("*/", cursor):
                depth -= 1
                cursor += 2
            else:
                cursor += 1
        return cursor
    if source[index] in ('"', "'"):
        quote = source[index]
        # Kotlin triple-quoted strings do not interpret escaped quotes.
        if quote == '"' and source.startswith('"""', index):
            end = source.find('"""', index + 3)
            return len(source) if end == -1 else end + 3
        cursor = index + 1
        while cursor < len(source):
            if source[cursor] == "\\":
                cursor += 2
            elif source[cursor] == quote:
                return cursor + 1
            else:
                cursor += 1
        return cursor
    return index


def _matching_delimiter(source, opening, left, right):
    depth = 0
    cursor = opening
    while cursor < len(source):
        skipped = _skip_quoted_or_commented(source, cursor)
        if skipped != cursor:
            cursor = skipped
            continue
        if source[cursor] == left:
            depth += 1
        elif source[cursor] == right:
            depth -= 1
            if depth == 0:
                return cursor
        cursor += 1
    raise AssertionError(f"Unclosed {left!r} delimiter in Kotlin source")


def _function(source, name):
    """Return one Kotlin function declaration, including its body."""
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if not match:
        raise AssertionError(f"Missing Kotlin function {name}")
    opening_paren = source.find("(", match.start())
    closing_paren = _matching_delimiter(source, opening_paren, "(", ")")
    opening_brace = source.find("{", closing_paren + 1)
    if opening_brace == -1:
        raise AssertionError(f"Function {name} has no body")
    closing_brace = _matching_delimiter(source, opening_brace, "{", "}")
    return source[match.start():closing_brace + 1]


def _body(source, name):
    declaration = _function(source, name)
    opening = declaration.find("{")
    return declaration[opening + 1:-1]


def _compact(source):
    """Ignore formatting-only whitespace when comparing Kotlin source contracts."""
    return re.sub(r"\s+", "", source)


class PicacgRankBadgeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.badge_path = JAVA / "ui/components/PicacgRankBadge.kt"
        cls.card_path = JAVA / "ui/components/PicacgMangaCard.kt"
        cls.browse_path = JAVA / "ui/picacg/PicacgBrowseScreen.kt"
        cls.reference_path = JAVA / "platform/YuzongRankContent.kt"
        cls.badge = cls.badge_path.read_text()
        cls.card = cls.card_path.read_text()
        cls.browse = cls.browse_path.read_text()
        cls.reference = cls.reference_path.read_text()

    def test_badge_copies_the_existing_rank_badge_style(self):
        declaration = _function(self.badge, "PicacgRankBadge")
        self.assertRegex(
            declaration,
            r"fun\s+PicacgRankBadge\s*\(\s*rank\s*:\s*Int\s*,\s*"
            r"modifier\s*:\s*Modifier\s*=\s*Modifier\s*\)",
        )
        self.assertRegex(self.badge, r"@Composable\s+internal\s+fun\s+PicacgRankBadge\s*\(")
        # The badge implementation is intentionally a copy: color mapping, dimensions,
        # padding, shape, alignment, and text treatment must stay in lockstep.
        self.assertEqual(
            _compact(_body(self.reference, "RankBadge")),
            _compact(_body(self.badge, "PicacgRankBadge")),
        )

    def test_card_accepts_optional_rank_and_places_badge_at_cover_top_start(self):
        card = _function(self.card, "PicacgMangaCard")
        self.assertRegex(card, r"rank\s*:\s*Int\?\s*=\s*null")
        self.assertIn(
            _compact("rank?.let { PicacgRankBadge(rank = it, modifier = Modifier.align(Alignment.TopStart)) }"),
            _compact(card),
        )

    def test_all_three_browse_layouts_propagate_nullable_rank_to_cover_badges(self):
        grid = _function(self.browse, "PicacgBrowseComicGridItem")
        list_item = _function(self.browse, "PicacgBrowseComicListItem")
        self.assertRegex(grid, r"rank\s*:\s*Int\?")
        self.assertRegex(list_item, r"rank\s*:\s*Int\?")

        # The comfortable grid uses the reusable card; other grid modes and list mode
        # render their own cover, so each cover presentation must handle rank directly.
        self.assertIn(_compact("PicacgMangaCard(manga = manga, onClick = onClick, rank = rank)"), _compact(grid))
        badge_call = _compact("rank?.let { PicacgRankBadge(rank = it, modifier = Modifier.align(Alignment.TopStart)) }")
        self.assertIn(badge_call, _compact(grid))
        self.assertIn(badge_call, _compact(list_item))

        # List covers must be a Box so the optional badge overlays the actual cover.
        list_box = list_item.find("Box(")
        list_badge = list_item.find("PicacgRankBadge(")
        self.assertGreaterEqual(list_box, 0, "List-mode cover should be hosted in a Box")
        self.assertGreater(list_badge, list_box, "List badge should be inside the cover Box")

    def test_only_timeframe_feeds_get_one_based_index_rank_and_item_order_is_preserved(self):
        screen = _function(self.browse, "PicacgBrowseScreen")
        compact_screen = _compact(screen)
        self.assertEqual(
            2,
            compact_screen.count("itemsIndexed(mangas,key={_,manga->manga.id}){index,manga->"),
            "Both list and grid must enumerate manga in source order with stable ID keys",
        )
        self.assertEqual(2, compact_screen.count("rank=if(mode.timeframe!=null)index+1elsenull"))
        self.assertIn("PicacgBrowseComicListItem(manga=manga,rank=if(mode.timeframe!=null)index+1elsenull)", compact_screen)
        self.assertIn("PicacgBrowseComicGridItem(manga=manga,displayMode=displayMode,rank=if(mode.timeframe!=null)index+1elsenull)", compact_screen)

        # Rank is derived from the original indexed feed, not a sorted/transformed copy.
        for forbidden in ("mangas.sorted", "mangas.sortedBy", "mangas.reversed(", "mangas.shuffled("):
            self.assertNotIn(forbidden, screen)
        self.assertIn("PicacgRepository.leaderboard(mode.timeframe!!, forceRefresh)", screen)
        self.assertIn("PicacgRepository.random()", screen)
        self.assertIn("PicacgRepository.browse(", screen)
        feed_modes = self.browse.split("internal enum class PicacgFeedMode", 1)[1].split("}", 1)[0]
        for mapping in ('LEADERBOARD_24H("H24")', 'LEADERBOARD_7D("D7")', 'LEADERBOARD_30D("D30")'):
            self.assertIn(mapping, feed_modes)
        self.assertRegex(feed_modes, r"\bRANDOM\s*,")
        self.assertRegex(feed_modes, r"\bLATEST\s*,")
        self.assertRegex(feed_modes, r"\bFILTERED\s*,")


if __name__ == "__main__":
    unittest.main()
