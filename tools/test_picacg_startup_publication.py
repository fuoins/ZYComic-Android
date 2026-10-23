#!/usr/bin/env python3
"""Run real PicacgSpeedTester publication contracts against isolated route fakes.

Uses only dependency jars already in Gradle's cache or tools/offline-tests/build/deps;
no Android app build or network access is needed.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests/picacg-startup-publication"
NET = ROOT / "app/src/main/java/com/zycomic/app/net"
DEPS = ROOT / "tools/offline-tests/build/deps"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def verify_production_route_source_contract() -> None:
    route = (NET / "PicacgRouteManager.kt").read_text()
    tester = (NET / "PicacgSpeedTester.kt").read_text()
    assert "internal fun clearAutomaticHealthSelections()" in route
    assert "PicacgRouteManager.clearAutomaticHealthSelections()" in tester
    assert "if (validCover.isEmpty() && validContent.isEmpty()) return false" not in route
    assert "val firstContent = validContent.firstOrNull()" in route
    assert "val first = validContent.firstOrNull() ?: validCover.first()" not in route
    body_route = route.split("private fun rankedIpFor(hostname: String?): String?", 1)[1].split("private fun persistedRanking", 1)[0]
    assert "state.coverRanking.firstOrNull { it.domain.equals(hostname, true) }" not in body_route
    assert "return state.contentRanking.firstOrNull { it.domain.equals(hostname, true) }?.ip" in body_route
    assert "adaptiveSession.prepareBlocking(quick = quickApi)" in tester
    assert "adaptiveSession.prepareBlocking(quick = quickImages)" in tester
    assert "freshImageHistory.isNotEmpty()" not in tester
    assert "reuseApi && !forceFresh" not in tester and "reuseImages && !forceFresh" not in tester


def jar(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    if not matches:
        raise RuntimeError(f"Missing cached dependency {group}:{name}:{version}; refusing to download")
    return str(matches[0])


def main() -> None:
    verify_production_route_source_contract()
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    junit = jar("org.junit.platform", "junit-platform-console-standalone", "1.10.2")
    compiler = os.pathsep.join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"), stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"), coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    classpath = os.pathsep.join([
        stdlib, junit, coroutines,
        jar("com.squareup.okhttp3", "okhttp", "4.12.0"),
        jar("com.squareup.okio", "okio-jvm", "3.6.0"),
        jar("org.json", "json", "20240303"),
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    harness_sources = sorted(path for path in HARNESS.glob("src/**/*.kt") if path.name != "Json.kt")
    production_names = [
        "DomainIp", "PicacgNetworkPolicy", "PicacgBuiltinNetwork", "PicacgImagePayloadPolicy",
        "PicacgSpeedTestTargetPolicy", "PicacgSpeedTestHistory", "SpeedTestLogSelectionPolicy",
        "SpeedTestLogSummary", "SpeedTestProbeCall", "RuntimeRecoveryPolicy", "AdaptiveSpeedProbe", "AdaptiveSpeedHistory", "PicacgSpeedTester",
    ]
    sources = harness_sources + [NET / f"{name}.kt" for name in production_names]
    sources += [ROOT / "app/src/test/java/com/zycomic/app/net/PicacgSpeedTesterRuntimeTest.kt"]
    missing = [str(path) for path in sources if not path.is_file()]
    if missing:
        raise RuntimeError("Missing harness source(s): " + ", ".join(missing))
    with tempfile.TemporaryDirectory(prefix="picacg-startup-publication-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        print(f"Compiling {len(sources)} isolated harness/production/test sources", flush=True)
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", str(classes), *map(str, sources),
        ], cwd=ROOT, check=True)
        subprocess.run([
            "java", "-jar", junit, "execute", "--class-path", str(classes) + os.pathsep + classpath,
            "--select-class", "com.zycomic.app.net.PicacgSpeedTesterRuntimeTest",
            "--select-class", "com.zycomic.app.net.AdaptiveSpeedHistoryPersistenceTest",
            "--disable-banner", "--details=summary", "--fail-if-no-tests",
        ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
