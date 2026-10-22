#!/usr/bin/env python3
"""Compile and run the isolated Picacg API and keyword repository contracts."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests"
PICACG = HARNESS / "picacg"
MANIFEST = PICACG / "dependencies.json"


def resolve_jar(dependency: dict[str, str], deps_dir: Path, cache_root: Path) -> Path:
    """Prefer the parent CI bootstrap output, then search the Gradle module cache."""
    filename = dependency["jar"]
    candidates = [deps_dir / filename]
    group, artifact, version = dependency["coordinate"].split(":")
    cache_module = cache_root / group / artifact / version
    if cache_module.exists():
        candidates.extend(sorted(cache_module.rglob(filename)))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    searched = "\n  ".join(str(path) for path in candidates)
    raise FileNotFoundError(
        f"Missing {dependency['coordinate']} ({filename}). Searched:\n  {searched}\n"
        "Run `./gradlew -p tools/offline-tests prepareHarnessDependencies` or set "
        "PICACG_HARNESS_DEPS to the directory containing the provisioned JARs."
    )


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    deps_dir = Path(
        os.environ.get("PICACG_HARNESS_DEPS", HARNESS / "build/deps")
    ).expanduser()
    cache_root = Path(
        os.environ.get(
            "GRADLE_MODULE_CACHE",
            Path.home() / ".gradle/caches/modules-2/files-2.1",
        )
    ).expanduser()
    jars = {
        dependency["key"]: resolve_jar(dependency, deps_dir, cache_root)
        for dependency in manifest["dependencies"]
    }

    stdlib = jars["kotlin-stdlib"]
    compiler_cp = os.pathsep.join(
        str(jars[key])
        for key in (
            "kotlin-compiler",
            "kotlin-stdlib",
            "kotlin-script-runtime",
            "kotlin-reflect",
            "kotlin-daemon",
            "coroutines",
            "annotations",
        )
    )
    test_cp = os.pathsep.join(
        str(jars[key])
        for key in (
            "kotlin-stdlib",
            "junit-console",
            "json",
            "okhttp",
            "okio",
            "coroutines",
            "annotations",
        )
    )

    app_main = ROOT / "app/src/main/java/com/zycomic/app"
    production_names = (
        "PicacgModels.kt",
        "PicacgAuthStore.kt",
        "PicacgClient.kt",
        "PicacgRepository.kt",
        "PicacgRegistration.kt",
        "PicacgRegistrationErrors.kt",
        "PicacgDiagnosticPolicy.kt",
        "PicacgRedirectPolicy.kt",
        "PicacgLoginErrors.kt",
        "PicacgLoginErrorPolicy.kt",
        "PicacgDetailCache.kt",
        "PicacgBrowseSnapshotCache.kt",
        "PicacgRecommendationLoader.kt",
    )
    production_sources = [app_main / "picacg" / name for name in production_names]
    production_sources.extend(
        [
            app_main / "picacg/filter/PicacgBlockKeywordStore.kt",
            app_main / "picacg/filter/PicacgKeywordFilter.kt",
            app_main / "reader/PicacgSource.kt",
            app_main / "reader/PicacgPageListProgress.kt",
            app_main / "reader/ReaderPageBatchListener.kt",
        ]
    )
    fixtures = sorted((PICACG / "fixtures").glob("*.kt"))
    tests = [
        PICACG / "PicacgOfflineApiTest.kt",
        PICACG / "PicacgKeywordRepositoryTest.kt",
    ]
    missing_sources = [path for path in [*production_sources, *fixtures, *tests] if not path.is_file()]
    if missing_sources:
        raise FileNotFoundError("Missing source files:\n" + "\n".join(map(str, missing_sources)))

    with tempfile.TemporaryDirectory(prefix="picacg-repository-tests-") as temp_dir:
        classes = Path(temp_dir) / "classes"
        classes.mkdir()
        run(
            [
                "java",
                "-cp",
                compiler_cp,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target",
                "17",
                "-classpath",
                test_cp,
                "-d",
                str(classes),
                *map(str, [*fixtures, *production_sources, *tests]),
            ]
        )
        run(
            [
                "java",
                "-jar",
                str(jars["junit-console"]),
                "execute",
                "--class-path",
                os.pathsep.join((str(classes), test_cp)),
                "--select-class",
                "com.zycomic.app.picacg.PicacgOfflineApiTest",
                "--select-class",
                "com.zycomic.app.picacg.PicacgKeywordRepositoryTest",
                "--disable-banner",
                "--fail-if-no-tests",
                "--details",
                "tree",
            ]
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        print(f"Picacg offline test runner failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
