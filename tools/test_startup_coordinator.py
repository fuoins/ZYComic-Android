#!/usr/bin/env python3
"""Compile and run offline integration tests against the production startup coordinator.

No Android build or network probes are used. The production coordinator and request
classes are compiled directly with the repository's Kotlin compiler cache; only Android,
route-manager and endpoint-tester boundaries are replaced with deterministic fakes.
"""
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests/startup-coordinator"
COMMON = ROOT / "tools/offline-tests/maccms-coordinator"
DEPS = ROOT / "tools/offline-tests/build/deps"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    if not matches:
        raise RuntimeError(f"Missing {group}:{name}:{version}; run ./gradlew -p tools/offline-tests prepareHarnessDependencies")
    return str(matches[0])


def main() -> None:
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    test = HARNESS / "StartupCoordinatorRuntimeTest.kt"
    fakes = [COMMON / name for name in [
        "MaccmsCoordinatorAndroid.kt", "MaccmsCoordinatorConnectivity.kt", "Log.kt",
        "MaccmsCoordinatorPlatform.kt", "MaccmsCoordinatorFakes.kt",
    ]]
    production = [net / f"{name}.kt" for name in [
        "DomainIp", "MaccmsPinnedTransport", "MaccmsSpeedTester", "CompletedSpeedTestPhases",
        "StartupSpeedTestGate", "StartupSpeedTestRequest", "SpeedTestRunPolicy", "FullSpeedTestRequest",
        "StartupTestStage",
        "PlatformStartupStatus", "PlatformSpeedTestCoordinator", "PlatformSpeedTestReport",
        "SpeedTestTimings", "SpeedTestTimingLog", "SpeedTestUiTextPolicy", "ImageSourceDisplayPolicy",
        "ImgDomainPolicy", "StartupTestMode",
    ]]
    sources = fakes + production + [test]
    for path in sources:
        if not path.is_file():
            raise RuntimeError(f"Missing coordinator source: {path}")

    selectors = []
    current_class = None
    for match in re.finditer(r"\bclass\s+(\w+)|@Test\s+fun\s+`([^`]+)`", test.read_text()):
        if match.group(1):
            current_class = match.group(1)
        else:
            if not current_class:
                raise RuntimeError(f"Test method has no enclosing class: {test}")
            selectors.append(f"com.zycomic.app.net.{current_class}#{match.group(2)}")
    if not selectors:
        raise RuntimeError("No startup coordinator runtime test methods were discovered")
    if len(selectors) != len(set(selectors)):
        raise RuntimeError("Duplicate startup coordinator runtime test selectors discovered")

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
    junit = jar("org.junit.platform", "junit-platform-console-standalone", "1.10.2")
    compiler = os.pathsep.join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"), stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"), coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    classpath = os.pathsep.join([
        stdlib, junit, coroutines, jar("org.json", "json", "20240303"),
        jar("com.squareup.okhttp3", "okhttp-jvm", "5.3.2"),
        jar("com.squareup.okio", "okio-jvm", "3.16.4"),
    ])

    with tempfile.TemporaryDirectory(prefix="zycomic-startup-coordinator-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        print(f"Compiling {len(sources)} sources (production coordinator + offline fakes + tests)", flush=True)
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", str(classes), *map(str, sources),
        ], cwd=ROOT, check=True)
        failed = []
        for selector in selectors:
            print("FRESH TEST JVM:", selector, flush=True)
            completed = subprocess.run([
                "java", "-jar", junit,
                "execute", "--class-path", str(classes) + os.pathsep + classpath,
                "--select-method=" + selector, "--disable-banner", "--details=summary", "--fail-if-no-tests",
            ], cwd=ROOT, check=False)
            if completed.returncode != 0:
                failed.append(selector)
    if failed:
        print(f"FAIL: {len(failed)}/{len(selectors)} methods: {', '.join(failed)}", flush=True)
        sys.exit(1)
    print(f"PASS: {len(selectors)} startup coordinator runtime methods; each ran in a fresh JVM", flush=True)


if __name__ == "__main__":
    main()
