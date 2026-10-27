#!/usr/bin/env python3
"""Offline source contracts and real Kotlin/JUnit regressions for browse tabs.

Run from any directory with: python3 /workspace/ZYComic-Android/tools/test_browse_tab_management.py
The runner uses only locally cached compiler/test artifacts; it never invokes Gradle or downloads dependencies.
Optional overrides: JUNIT_CONSOLE_JAR, ANDROID_API_JAR, JSON_JAR, KOTLIN_VERSION.
"""
from pathlib import Path
import glob
import os
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java"
TEST = ROOT / "app/src/test/java"
FAKES = ROOT / "tools/offline-tests/browse-tab-management"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
DEPS = ROOT / "tools/offline-tests/build/deps"
BROWSE_DIR = MAIN / "com/zycomic/app/ui/browse"
PICACG_DIR = MAIN / "com/zycomic/app/ui/picacg"
STRINGS = ROOT / "i18n-kmk/src/commonMain/moko-resources/base/strings.xml"
SHEET = (BROWSE_DIR / "BrowseTabManageSheet.kt").read_text()
BROWSE = (BROWSE_DIR / "BrowseScreen.kt").read_text()
BROWSE_VM = (BROWSE_DIR / "BrowseViewModel.kt").read_text()
PICACG = (PICACG_DIR / "PicacgBrowseScreen.kt").read_text()
JM_POLICY = (BROWSE_DIR / "JmBrowsePolicy.kt").read_text()
STRINGS_XML = STRINGS.read_text()
JM_SOURCE = (MAIN / "com/zycomic/app/reader/JmSource.kt").read_text()
TAB_MODELS = (BROWSE_DIR / "BrowseTabModels.kt").read_text()


def _function_body(source: str, signature: str, next_signature: str) -> str:
    """Return a source slice bounded by stable declaration markers."""
    start = source.index(signature)
    end = source.find(next_signature, start + len(signature))
    return source[start:] if end < 0 else source[start:end]


class BrowseTabManagementLayoutTest(unittest.TestCase):
    def test_name_hint_is_retained_when_name_is_empty_or_duplicate(self):
        helper = SHEET.split("private fun TabNameSupportingText(", 1)[1].split("\n}", 1)[0]
        self.assertIn("error?.let { Text(it) }", helper)
        self.assertIn("KMR.strings.browse_tab_name_hint", helper)
        self.assertIn('trimmed.isEmpty() -> "名称不能为空"', SHEET)
        self.assertIn('"名称已存在"', SHEET)

    def test_restore_default_is_before_reorder_action(self):
        manage_actions = SHEET.split('Text("管理已有标签页', 1)[1].split("\n            }", 1)[0]
        self.assertLess(manage_actions.index('Text("恢复默认")'), manage_actions.index('Text("更改顺序")'))

    def test_managed_tab_pill_has_minimum_48dp_height_and_targets(self):
        pill = SHEET.split("private fun ManagedTabPill(", 1)[1]
        self.assertIn("Modifier.heightIn(min = 48.dp)", pill)
        # 编辑 / 背景源 / 删除三个动作按钮
        self.assertIn("Icons.Outlined.Edit", pill)
        self.assertIn("Icons.Outlined.Photo", pill)
        self.assertIn("Icons.Outlined.Delete", pill)
        self.assertIn("onBackground: () -> Unit", pill)

    def test_background_sources_are_editable_per_tab_and_persisted_with_the_tab(self):
        self.assertIn("backgroundSources", TAB_MODELS)
        self.assertIn('put("backgroundSources", JSONArray(backgroundSources))', TAB_MODELS)
        self.assertIn('optJSONArray("backgroundSources")', TAB_MODELS)
        self.assertIn("fun BrowseTabItem.withDefaultBackgrounds", TAB_MODELS)
        self.assertIn("withDefaultBackgrounds()", (BROWSE_DIR / "BrowseTabStore.kt").read_text())
        self.assertIn("fun updateTabBackgroundSources", BROWSE_VM)
        self.assertIn("BackgroundSourcesDialog(", SHEET)
        self.assertIn("vm.updateTabBackgroundSources(item.id, sources.toList())", SHEET)
        self.assertIn("initialBackgroundSources", SHEET)
        # 全局随机背景与玻璃底栏在主界面
        main_activity = (MAIN / "com/zycomic/app/ui/MainActivity.kt").read_text()
        self.assertIn("AppCoverBackdrop(url = backdropUrl", main_activity)
        self.assertIn("GlassBottomBar {", main_activity)
        self.assertIn("AppBackdropState.randomFrom(sources)", main_activity)
        # 浏览页把封面注册进全局候选池
        self.assertIn("AppBackdropState.registerCovers(tabPlatform.id", BROWSE)

    def test_filter_summary_uses_high_contrast_theme_colors(self):
        form = SHEET.split("private fun FilterTabFormDialog(", 1)[1].split("\n@Composable\nprivate fun TabNameSupportingText", 1)[0]
        self.assertIn("Surface(", form)
        self.assertIn("color = MaterialTheme.colorScheme.primary", form)
        self.assertIn("contentColor = MaterialTheme.colorScheme.onPrimary", form)
        self.assertIn('"全部主题"', form)

    def test_picacg_category_filter_entry_uses_stable_identity_not_display_name(self):
        # Identity helpers allow arbitrary display names and keep the category feed inline.
        self.assertIn("item.isPicacgCategory()", BROWSE)
        self.assertIn("item.platformId == ContentPlatform.PICACG && !item.isPicacgRanking()", BROWSE)
        self.assertIn("picacgFilterDialogRequest++", BROWSE)
        self.assertIn("BrowsePageType.PICACG_CATEGORY", TAB_MODELS)
        self.assertIn("PicacgBrowsePageMode.CATEGORY", BROWSE)
        self.assertNotIn('Text("哔咔分类")', PICACG)
        self.assertNotIn('AppBarTitle("分类")', BROWSE)

    def test_legacy_and_new_rankings_share_editor_and_persist_selected_mode(self):
        self.assertIn("picacgRanking = item.isPicacgRanking()", SHEET)
        self.assertIn("initialRankingMode = item.picacgRankingMode", SHEET)
        self.assertIn("vm.updateTabDefaultPicacgRankingMode(item.id, rankingMode)", SHEET)
        editor = _function_body(SHEET, "private fun PicacgTabRankingEditor(", "private fun TabNameSupportingText(")
        self.assertIn("PicacgRankingMode.entries.forEach", editor)
        self.assertIn("selected = mode == option", editor)
        self.assertIn("initialRankingMode.toFeedMode()", PICACG)
        self.assertIn("PicacgRankingChips(selectedMode = (item?.picacgRankingMode ?: PicacgRankingMode.H24).toFeedMode())", BROWSE)

    def test_jm_add_and_edit_category_forms_keep_sort_visible_and_save_draft(self):
        editor = _function_body(SHEET, "private fun JmTabFilterEditor(", "private fun FilterTabFormDialog(")
        self.assertIn('FilterSection("JM 排序")', editor)
        self.assertNotIn("if (!categoryOnly)", editor)
        self.assertIn("onChange(filters.copy(order = value))", editor)
        self.assertIn("JmBrowsePolicy.customCategoryFilters(jmDraft)", SHEET)
        self.assertIn("vm.updateTabDefaultJmFilters(item.id, jmFilters)", SHEET)

    def test_small_screen_scrolls_form_content_while_retaining_save_action(self):
        form = SHEET.split("private fun FilterTabFormDialog(", 1)[1]
        self.assertIn("Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))", form)
        self.assertLess(form.index("verticalScroll(rememberScrollState())"), form.index("OutlinedTextField("))
        self.assertIn('Text("保存")', form)
        pill = SHEET.split("private fun ManagedTabPill(", 1)[1]
        self.assertIn("overflow = TextOverflow.Ellipsis", pill)
        self.assertIn("Modifier.weight(1f, fill = false)", pill)

    def test_category_editor_keeps_all_themes_and_api_sort_values(self):
        editor = _function_body(SHEET, "private fun PicacgTabFilterEditor(", "private fun TabReorderDialog(")
        self.assertIn("PicacgThemes.titles.forEach", editor)
        self.assertIn("filter.copy(category = theme)", editor)
        for code in ("dd", "da", "ld", "vd"):
            self.assertIn(f'"{code}" to ', editor)
        self.assertIn("filter.copy(sort = code)", editor)

    def test_shared_display_controls_are_saved_by_viewmodel_and_forwarded_by_screens(self):
        self.assertIn("fun setDisplayMode(mode: Int)", BROWSE_VM)
        self.assertIn('prefs.edit().putInt("browse_display_mode", mode).apply()', BROWSE_VM)
        self.assertIn("fun setGridColumns(cols: Int, orientation: Int)", BROWSE_VM)
        self.assertIn('onSetDisplayMode = vm::setDisplayMode', BROWSE)
        self.assertIn('onSetGridColumns = { vm.setGridColumns(it, picacgOrientation) }', BROWSE)
        self.assertIn("onSetDisplayMode: (Int) -> Unit", PICACG)
        self.assertIn("onSetGridColumns: (Int) -> Unit", PICACG)
        self.assertIn("onSetDisplayMode = onSetDisplayMode", PICACG)
        self.assertIn("onSetGridColumns = onSetGridColumns", PICACG)

    def test_jm_session_filter_update_does_not_persist_defaults(self):
        update = _function_body(BROWSE_VM, "fun updateJmFilters(", "fun loadJmWeekIssues(")
        self.assertIn("stateFor(tab).jmFilters.value = current.copy(", update)
        self.assertIn("refresh(tab)", update)
        self.assertNotIn("saveJmFilters", update)

    def test_jm_category_values_come_from_api_value_not_human_label(self):
        category_options = _function_body(JM_POLICY, "fun categoryOptions(", "fun categorySelections(")
        self.assertIn("source.map { (label, value) ->", category_options)
        self.assertIn("else -> label", category_options)
        self.assertIn("to if (value.isEmpty()) LATEST_A_CATEGORY else value", category_options)
        self.assertNotIn("category = label", JM_POLICY)
        self.assertNotIn("category = section.title", JM_SOURCE)

    def test_custom_jm_tabs_show_categories_without_exposing_other_browse_modes(self):
        self.assertIn("fun showsCategoryPicker(tabId: String): Boolean = true", JM_POLICY)
        self.assertIn("if (JmBrowsePolicy.showsCategoryPicker(tabId))", BROWSE)
        self.assertIn("fun showsModePicker(tabId: String): Boolean = tabId != ALL_HOT_TAB_ID && !isCustomCategoryTab(tabId)", JM_POLICY)
        self.assertIn("if (tabId == ALL_HOT_TAB_ID || isCustomCategoryTab(tabId))", JM_POLICY)
        self.assertIn("mode = MODE_CATEGORY", JM_POLICY)

    def test_manage_sheet_keeps_title_and_close_fixed_while_only_list_scrolls(self):
        sheet = _function_body(SHEET, "fun TabManageSheet(", "// ---- 删除确认 ----")
        header = sheet.index('Text("标签页管理"')
        close = sheet.index('IconButton(onClick = onDismiss)')
        listing = sheet.index("LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth())")
        self.assertLess(header, listing)
        self.assertLess(close, listing)
        self.assertIn(".fillMaxHeight(0.9f)", sheet)
        self.assertNotIn("verticalScroll(", sheet)

    def test_added_tab_is_selected_without_accepting_a_stale_pager_page(self):
        self.assertIn("programmaticTabSelection = index.takeUnless { it == pagerState.settledPage }", BROWSE)
        # 新增 tab 后必须先等 pager 的 pageCount 覆盖目标页，否则会被旧上限截断到前一个 tab。
        self.assertIn("snapshotFlow { pagerState.pageCount }.first { it > target }", BROWSE)
        self.assertIn("pagerState.scrollToPage(target)", BROWSE)
        self.assertIn("if (page == programmaticTabSelection) programmaticTabSelection = null", BROWSE)
        self.assertIn("if (page == vm.mainTab.value) return@collect", BROWSE)

    def test_obsolete_jm_browse_interface_pending_copy_is_removed(self):
        self.assertNotIn("禁漫天堂接口待接入", STRINGS_XML)
        self.assertNotIn('name="content_platform_browse_detail"', STRINGS_XML)
        self.assertNotIn('name="content_platform_not_connected_detail"', STRINGS_XML)



def _cached_jar(group: str, artifact: str, version: str | None = None) -> Path | None:
    version_path = CACHE / group / artifact
    if not version_path.exists():
        return None
    if version:
        matches = sorted(version_path.glob(f"{version}/**/*.jar"))
        if matches:
            return matches[0]
    matches = sorted(version_path.glob("*/*/*.jar"))
    return matches[-1] if matches else None


def _required_jar(env: str, description: str, candidates: list[Path | None]) -> Path:
    value = os.environ.get(env)
    if value:
        path = Path(value).expanduser()
        if path.is_file():
            return path
        raise SystemExit(f"{env} points to a missing file: {path}")
    for candidate in candidates:
        if candidate and candidate.is_file():
            return candidate
    raise SystemExit(
        f"Could not find {description}. Set {env} to a local jar path; no dependencies are downloaded."
    )


def _cached_candidates(group: str, artifact: str) -> list[Path]:
    root = CACHE / group / artifact
    return sorted(root.glob("*/*/*.jar")) if root.exists() else []


def run_offline_kotlin_tests() -> None:
    if os.environ.get("BROWSE_TAB_SKIP_KOTLIN") == "1":
        print("Skipping offline Kotlin/JUnit compilation because BROWSE_TAB_SKIP_KOTLIN=1")
        return

    junit = _required_jar(
        "JUNIT_CONSOLE_JAR",
        "junit-platform-console-standalone",
        [DEPS / "junit-platform-console-standalone-1.10.2.jar",
         *_cached_candidates("org.junit.platform", "junit-platform-console-standalone")],
    )
    sdk_roots = {
        Path(value).expanduser()
        for value in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"))
        if value
    }
    sdk_android_jars = sorted(
        (jar for root in sdk_roots for jar in (root / "platforms").glob("*/android.jar")),
        reverse=True,
    )
    android_api = _required_jar(
        "ANDROID_API_JAR",
        "an Android API jar containing android.content.Context/SharedPreferences",
        [*sdk_android_jars],
    )
    json_jar = _required_jar(
        "JSON_JAR",
        "org.json/json",
        [DEPS / "json-20240303.jar", *_cached_candidates("org.json", "json")],
    )

    kotlin_version = os.environ.get("KOTLIN_VERSION", "2.2.21")
    stdlib = _required_jar("KOTLIN_STDLIB_JAR", "Kotlin stdlib", [_cached_jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin_version)])
    compiler = _required_jar("KOTLIN_COMPILER_JAR", "Kotlin compiler embeddable", [_cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin_version)])
    compiler_support = [
        _required_jar("KOTLIN_SCRIPT_RUNTIME_JAR", "Kotlin script runtime", [_cached_jar("org.jetbrains.kotlin", "kotlin-script-runtime", kotlin_version)]),
        _required_jar("KOTLIN_REFLECT_JAR", "Kotlin reflect", [_cached_jar("org.jetbrains.kotlin", "kotlin-reflect", kotlin_version), _cached_jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10")]),
        _required_jar("KOTLIN_DAEMON_JAR", "Kotlin daemon embeddable", [_cached_jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", kotlin_version)]),
        _required_jar("KOTLIN_COROUTINES_JAR", "Kotlin compiler coroutines runtime", [_cached_jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]),
        _required_jar("KOTLIN_ANNOTATIONS_JAR", "JetBrains annotations", [_cached_jar("org.jetbrains", "annotations", "13.0")]),
    ]

    # Real org.json must precede the Android stub jar, whose org.json.* only throws "Stub!".
    compile_classpath = os.pathsep.join(map(str, [stdlib, junit, json_jar, android_api]))
    compiler_classpath = os.pathsep.join(map(str, [compiler, stdlib, *compiler_support]))
    sources = [
        # Real production policies/models; only framework-facing API/data types are faked.
        BROWSE_DIR / "BrowseTabModels.kt",
        BROWSE_DIR / "BrowseTabStore.kt",
        BROWSE_DIR / "BrowseTabPolicy.kt",
        BROWSE_DIR / "JmBrowsePolicy.kt",
        BROWSE_DIR / "JmFilterSnapshot.kt",
        MAIN / "com/zycomic/app/reader/JmBrowseParameters.kt",
        BROWSE_DIR / "SessionTabFilters.kt",
        PICACG_DIR / "PicacgBrowsePrefs.kt",
        PICACG_DIR / "PicacgThemes.kt",
        FAKES / "ContentPlatformFake.kt",
        FAKES / "JmSourceFake.kt",
        FAKES / "ModelFakes.kt",
        FAKES / "PicacgCategoryFake.kt",
        TEST / "com/zycomic/app/ui/browse/BrowseTabManagementRegressionTest.kt",
        TEST / "com/zycomic/app/ui/browse/BrowseTabPersistenceTest.kt",
        TEST / "com/zycomic/app/ui/browse/JmBrowsePolicyTest.kt",
        TEST / "com/zycomic/app/ui/browse/JmFilterSnapshotTest.kt",
        TEST / "com/zycomic/app/ui/browse/SessionTabFiltersTest.kt",
        TEST / "com/zycomic/app/ui/picacg/PicacgBrowsePrefsTest.kt",
    ]
    missing = [str(source) for source in sources if not source.is_file()]
    if missing:
        raise SystemExit("Required offline test source(s) missing:\n  " + "\n  ".join(missing))

    output = Path(os.environ.get("BROWSE_TAB_CLASSES_DIR", "/tmp/browse-tab-management-offline/classes"))
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    compile_cmd = [
        "java", "-cp", compiler_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", compile_classpath,
        "-d", str(output), *map(str, sources),
    ]
    print("Compiling production policies and selected tests offline...", flush=True)
    subprocess.run(compile_cmd, cwd=ROOT, check=True)
    # Real org.json must precede the Android stub jar, whose org.json.* only throws "Stub!".
    runtime_classpath = os.pathsep.join(map(str, [output, stdlib, junit, json_jar, android_api]))
    print("Running selected JUnit tests...", flush=True)
    subprocess.run(
        ["java", "-jar", str(junit), "execute", "--class-path", runtime_classpath,
         "--scan-class-path", "--disable-banner", "--fail-if-no-tests"],
        cwd=ROOT,
        check=True,
    )


class OfflineKotlinTest(unittest.TestCase):
    def test_real_production_policies_compile_and_targeted_tests_pass(self):
        run_offline_kotlin_tests()


if __name__ == "__main__":
    unittest.main(verbosity=2)
