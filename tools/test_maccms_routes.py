#!/usr/bin/env python3
"""Compile/run focused Maccms tests with cached dependencies; never invokes Gradle or downloads."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
LOCAL_DEPS = Path("/tmp/jm-targeted-check/deps")
OUT = Path("/tmp/jm-maccms-offline-tests")
CLASSES = OUT / "classes"


def jar(group: str, name: str, version: str) -> str:
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    local = LOCAL_DEPS / f"{name}-{version}.jar"
    if not matches and local.is_file():
        matches = [local]
    if not matches:
        raise RuntimeError(
            f"Cached dependency not found: {group}:{name}:{version}; this runner is offline"
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
            raise AssertionError("Coordinator is missing the independent Maccms quick/full run")
        for contract in ("isCurrent =", "beforeApply ="):
            if contract not in text:
                raise AssertionError(f"Maccms coordinator call is missing {contract}")
    log_screen = ROOT / "app/src/main/java/com/zycomic/app/ui/settings/MaccmsSpeedTestLogScreen.kt"
    if log_screen.exists():
        text = log_screen.read_text(encoding="utf-8")
        if "MaccmsSpeedTester.logJson()" not in text or "MaccmsSpeedTester.run(quick = false)" not in text:
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
    ) if path.exists()]
    tests = sorted(test_net.glob("Maccms*Test.kt")) + sorted(test_picacg.glob("Maccms*Test.kt"))
    tests += sorted(offline_tests.glob("Maccms*Test.kt"))
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
    json = str(LOCAL_DEPS / "json-20240303.jar")
    junit = "/tmp/jm-targeted-check/junit-console.jar"
    if not Path(json).is_file() or not Path(junit).is_file():
        raise RuntimeError("Cached JSON/JUnit runner jar missing; this runner is offline")
    compiler = ":".join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    cp = ":".join([stdlib, coroutines, okhttp, okio, json, junit])
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
