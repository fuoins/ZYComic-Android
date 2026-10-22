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
    assert "PicacgRepository.random()" in browse_screen
    assert "PicacgRepository.browseCacheRevision.collectAsState()" in browse_screen
    assert "routeState.revision" in browse_screen
    print("Picacg browse/search/rank cache policy and condition-fenced integration wiring: OK")


if __name__ == "__main__":
    main()
