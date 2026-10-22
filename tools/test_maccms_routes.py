#!/usr/bin/env python3
"""Compile/run focused Maccms tests with cached dependencies; never invokes Gradle or downloads."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / "tools/offline-tests/build/deps"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
OUT = Path("/tmp/jm-maccms-offline-tests")
CLASSES = OUT / "classes"


def jar(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    if not matches:
        raise RuntimeError(
            f"Missing {group}:{name}:{version}; run prepareHarnessDependencies first"
        )
    return str(matches[0])


def source_contracts() -> None:
    """Check task-level orchestration/UI wiring when the parent implementation exists."""
    tester = ROOT / "app/src/main/java/com/zycomic/app/net/MaccmsSpeedTester.kt"
    if tester.exists():
        text = tester.read_text(encoding="utf-8")
        for contract in ("quick", "isCurrent", "beforeApply"):
            if contract not in text:
                raise AssertionError(f"MaccmsSpeedTester is missing run contract: {contract}")
        if 'PREFS = "zycomic_maccms_speed_test"' not in text:
            raise AssertionError("Maccms speed history must use a distinct preference namespace")
    startup = ROOT / "app/src/main/java/com/zycomic/app/net/PlatformStartupStatus.kt"
    if startup.exists() and "MACCMS_LINE" not in startup.read_text(encoding="utf-8"):
        raise AssertionError("Platform startup status does not expose MACCMS_LINE")
    coordinator = ROOT / "app/src/main/java/com/zycomic/app/net/PlatformSpeedTestCoordinator.kt"
    if coordinator.exists():
        text = coordinator.read_text(encoding="utf-8")
        if "MaccmsSpeedTester.run(" not in text or "quick = reuseLines" not in text:
            raise AssertionError("Coordinator is missing the Maccms quick/full host check")
        for contract in ("isCurrent =", "beforeApply ="):
            if contract not in text:
                raise AssertionError(f"Maccms coordinator call is missing {contract}")
        if "onApiPublished = { endpoint -> publishMaccmsRoute(endpoint?.ip) }" not in text:
            raise AssertionError("Maccms must consume the formally published current-round API selection")
        if "publishMaccmsRoute(null)" not in text or "MaccmsSpeedTester.invalidateLines3Ip()" not in text:
            raise AssertionError("Missing current-run invalidation or no-IP path")
        if "maccmsHealthJob = healthScope.launch" not in text or "MaccmsSpeedTester.bindLines3Ip(lines3Ip)" not in text:
            raise AssertionError("Route readiness must be independent of background host health probes")
        cache_branch = text.split("if (linesDone && imagesDone) {", 1)[1].split("measuredPlatforms.add", 1)[0]
        if "platform != ContentPlatform.PICACG" not in cache_branch or "publishMaccmsRoute(null)" in cache_branch:
            raise AssertionError("A completed Picacg report must not skip current-round IP revalidation")
        picacg_run = text.split("val snapshot = PicacgSpeedTester.run(", 1)[1].split("picacgSnapshot =", 1)[0]
        if "reuseApi = false" not in picacg_run:
            raise AssertionError("Maccms requires a newly validated lines3 IP, never cached API probes")
    log_screen = ROOT / "app/src/main/java/com/zycomic/app/ui/settings/MaccmsSpeedTestLogScreen.kt"
    if log_screen.exists():
        text = log_screen.read_text(encoding="utf-8")
        if "MaccmsSpeedTester.logJson()" not in text or "PlatformSpeedTestCoordinator.run(forceFresh = true, onlyPlatform = ContentPlatform.PICACG)" not in text:
            raise AssertionError("The dedicated Maccms log screen must expose its own full scan and JSON export")
    # Route logs must have a distinct storage key, not alias one of the existing histories.
    history_candidates = sorted((ROOT / "app/src/main/java/com/zycomic/app/net").glob("Maccms*History*.kt"))
    for path in history_candidates:
        text = path.read_text(encoding="utf-8")
        if "PREFS" in text and "maccms" not in text.lower():
            raise AssertionError(f"Maccms history should use an independent preference key: {path.name}")


def main() -> None:
    source_contracts()
    main_net = ROOT / "app/src/main/java/com/zycomic/app/net"
    main_picacg = ROOT / "app/src/main/java/com/zycomic/app/picacg"
    test_net = ROOT / "app/src/test/java/com/zycomic/app/net"
    test_picacg = ROOT / "app/src/test/java/com/zycomic/app/picacg"
    offline_tests = ROOT / "tools/offline-tests"
    platform_stub = OUT / "MaccmsPlatformStub.kt"
    android_stub = OUT / "MaccmsAndroidStub.kt"
    dev_config_stub = OUT / "MaccmsDevConfigStub.kt"

    # Include only dedicated Maccms production/test sources, plus the small shared endpoint model.
    sources = sorted(main_net.glob("Maccms*.kt"))
    sources += [main_net / "DomainIp.kt"]
    sources += [path for path in (
        main_picacg / "MaccmsRecommendationClient.kt",
        main_picacg / "MaccmsRecommendationRouter.kt",
        main_picacg / "PicacgRecommendationLoader.kt",
    ) if path.exists()]
    tests = sorted(test_net.glob("Maccms*Test.kt")) + sorted(test_picacg.glob("Maccms*Test.kt"))
    tests += sorted(offline_tests.glob("Maccms*Test.kt"))
    tests += [test_picacg / "PicacgRecommendationTest.kt"]
    startup_status = ROOT / "app/src/main/java/com/zycomic/app/net/PlatformStartupStatus.kt"
    startup_test = test_net / "StartupCheckStatusTest.kt"
    if startup_status.exists() and startup_test.exists():
        sources += [startup_status, startup_test]
    if not tests:
        raise RuntimeError("No Maccms*Test.kt tests found; refusing to report a vacuous pass")
    sources += tests
    missing = [str(path) for path in sources if not path.is_file()]
    if missing:
        raise RuntimeError("Missing Kotlin source(s): " + ", ".join(missing))

    OUT.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(CLASSES, ignore_errors=True)
    CLASSES.mkdir(parents=True)
    platform_stub.write_text("""package com.zycomic.app.platform
enum class ContentPlatform { MANWA, JM, PICACG }
""", encoding="utf-8")
    android_stub.write_text("""package android.content
open class Context {
    companion object { const val MODE_PRIVATE: Int = 0 }
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences? = null
}
interface SharedPreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun edit(): Editor
    interface Editor { fun putString(key: String, value: String?): Editor; fun apply() }
}
""", encoding="utf-8")
    dev_config_stub.write_text("""package com.zycomic.app.net
object DevConfig { var appContext: android.content.Context? = null }
""", encoding="utf-8")

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    okhttp = jar("com.squareup.okhttp3", "okhttp-jvm", "5.3.2")
    okio = jar("com.squareup.okio", "okio-jvm", "3.16.4")
    json = jar("org.json", "json", "20240303")
    junit = jar("org.junit.platform", "junit-platform-console-standalone", "1.10.2")
    compiler = ":".join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    mockwebserver = jar("com.squareup.okhttp3", "mockwebserver3", "5.3.2")
    cp = ":".join([stdlib, coroutines, okhttp, okio, json, junit, mockwebserver])
    sources += [platform_stub, android_stub, dev_config_stub]
    print(f"Compiling {len(sources)} focused Kotlin source/test files with cached Kotlin 2.2.21")
    subprocess.run([
        "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
        "-d", str(CLASSES), *map(str, sources),
    ], cwd=ROOT, check=True)
    subprocess.run([
        "java", "-jar", junit, "execute", "--class-path", ":".join([str(CLASSES), cp]),
        "--scan-class-path", "--disable-banner", "--fail-if-no-tests",
        "--reports-dir", str(OUT / "reports"),
    ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
