#!/usr/bin/env python3
"""Offline focused runner for PicacgBrowseSnapshotCache.kt; does not invoke Gradle."""

from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def gradle_home() -> Path:
    user_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates = sorted(user_home.glob("wrapper/dists/gradle-*-bin/*/gradle-*"))
    for candidate in candidates:
        if (candidate / "lib/kotlin-compiler-embeddable-2.2.21.jar").is_file():
            return candidate
    raise RuntimeError("Cached Gradle Kotlin compiler was not found; this runner is offline-only")


def main() -> None:
    home = gradle_home()
    jars = sorted((home / "lib").glob("*.jar")) + sorted((home / "lib/plugins").glob("*.jar"))
    compiler_classpath = os.pathsep.join(str(path) for path in jars)
    stdlib = home / "lib/kotlin-stdlib-2.2.21.jar"
    production = ROOT / "app/src/main/java/com/zycomic/app/picacg/PicacgBrowseSnapshotCache.kt"
    test = ROOT / "tools/offline-tests/PicacgBrowseSnapshotCacheTest.kt"

    with tempfile.TemporaryDirectory(prefix="picacg-browse-cache-") as temp:
        output = Path(temp) / "classes"
        output.mkdir()
        subprocess.run(
            [
                "java", "-cp", compiler_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(stdlib),
                "-d", str(output), str(production), str(test),
            ],
            check=True,
            cwd=ROOT,
        )
        subprocess.run(
            ["java", "-cp", os.pathsep.join((str(output), str(stdlib))), "com.zycomic.app.picacg.PicacgBrowseSnapshotCacheTestKt"],
            check=True,
            cwd=ROOT,
        )

    repository = (ROOT / "app/src/main/java/com/zycomic/app/picacg/PicacgRepository.kt").read_text()
    browse_screen = (ROOT / "app/src/main/java/com/zycomic/app/ui/picacg/PicacgBrowseScreen.kt").read_text()
    assert "PicacgBrowseSnapshotCache<PicacgPage<Manga>>()" in repository
    assert "PicacgBlockKeywordStore.revision.value" in repository
    assert "routeConfigRevision = PicacgRouteManager.configRevision" in repository
    assert repository.count("expectedSessionRevision = key.condition.sessionRevision") >= 3
    assert "result.cancel(CancellationException(\"Picacg browse conditions changed while loading\"))" in repository
    assert "condition.blockedKeywords == PicacgBlockKeywordStore.keywords.value" in repository
    assert "configGeneration = browseConfigGeneration" in repository
    assert "return PicacgBrowseCacheKey(condition, operation, mode, query, sort, filters.toList(), page)" in repository
    browse_key = repository.split("internal fun browseSnapshotKey(", 1)[1].split("internal fun leaderboardSnapshotKey(", 1)[0]
    for dimension in ("operation = if (category.isNullOrBlank())", "mode = if (category.isNullOrBlank())", "query = listOfNotNull(category, tag, author, creatorId, chineseTeam)", "sort = sort", "filters = listOf(category.orEmpty(), tag.orEmpty(), author.orEmpty(), creatorId.orEmpty(), chineseTeam.orEmpty())", "page = page"):
        assert dimension in browse_key, f"browse snapshot key is missing {dimension}"
    search_key = repository.split("suspend fun search(", 1)[1].split("suspend fun leaderboard(", 1)[0]
    for dimension in ('operation = "search"', 'mode = "advanced"', "query = keyword", "sort = sort", "filters = selectedCategories", "page = page"):
        assert dimension in search_key, f"search snapshot key is missing {dimension}"
    assert "is PicacgBrowseCacheLookup.Fresh -> cached.value" in repository
    assert "is PicacgBrowseCacheLookup.Stale -> cached.value" in repository
    assert "entries[key] = PicacgBrowseSnapshot(value, nowMillis)" in (ROOT / "app/src/main/java/com/zycomic/app/picacg/PicacgBrowseSnapshotCache.kt").read_text()
    assert "suspend fun random(): List<Manga>" in repository
    random_loader = repository.split("suspend fun random(): List<Manga>", 1)[1].split("private fun browseCacheKey", 1)[0]
    assert "client().request(\"/comics/random\")" in random_loader
    assert "loadBrowseSnapshot" not in random_loader
    assert "PicacgRepository.random()" in browse_screen
    assert "PicacgFeedMode.RANDOM -> null" in browse_screen
    assert "val initialSnapshot = remember(queryIdentity)" in browse_screen
    assert "var pageSnapshots by remember(queryIdentity) { mutableStateOf(mapOf(1 to initialSnapshot?.items.orEmpty())) }" in browse_screen
    assert "pageSnapshots.filterKeys { it <= page }.toSortedMap()" in browse_screen
    assert "pageSnapshots + (requestedPage to pageItems)" in browse_screen
    assert "var hasSnapshot by remember(queryIdentity) { mutableStateOf(initialSnapshot != null) }" in browse_screen
    assert "if (loading && mangas.isEmpty() && !hasSnapshot)" in browse_screen
    assert browse_screen.index("val initialSnapshot = remember(queryIdentity)") < browse_screen.index("LaunchedEffect(queryIdentity, page, refresh, browseCacheRevision)")
    assert "currentRequest == requestIdentity" in browse_screen
    assert "PicacgRepository.browseConditionIsCurrent(condition)" in browse_screen
    assert "currentCoroutineContext().ensureActive()" in browse_screen
    assert "if (requestIsCurrent()) error = failure.message ?: loadFailed" in browse_screen
    assert "if (requestIsCurrent()) loading = false" in browse_screen
    assert "PicacgRepository.browseCacheRevision.collectAsState()" in browse_screen
    assert "routeState.revision" in browse_screen
    print("Picacg browse/search/rank exact snapshot identity, empty-snapshot presentation, stale retention, random bypass, and condition-fenced refresh: OK")


if __name__ == "__main__":
    main()
