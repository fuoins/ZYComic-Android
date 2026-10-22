#!/usr/bin/env python3
"""Run production coordinator contracts against isolated fakes, never the Android app classpath.

Prepare dependencies with ./gradlew -p tools/offline-tests prepareHarnessDependencies.
Every method runs in a fresh JVM because the production coordinator owns process-wide state.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests/maccms-coordinator"
DEPS = ROOT / "tools/offline-tests/build/deps"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    if not matches:
        raise RuntimeError(f"Missing {group}:{name}:{version}; run prepareHarnessDependencies first")
    return str(matches[0])


def main() -> None:
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    test_names = [
        "CompletedSpeedTestCoordinatorRuntimeTest",
        "StartupSpeedTestSharingRuntimeTest",
        "CoordinatorRetestAllRuntimeTest",
        "StartupCompletionToastRuntimeTest",
        "MaccmsCoordinatorRuntimeTest",
    ]
    tests = [HARNESS / f"{name}.kt" for name in test_names]
    tests.append(ROOT / "app/src/test/java/com/zycomic/app/net/SpeedTestRunPolicyTest.kt")
    sources = [HARNESS / name for name in [
        "MaccmsCoordinatorAndroid.kt", "MaccmsCoordinatorConnectivity.kt", "Log.kt",
        "MaccmsCoordinatorPlatform.kt", "MaccmsCoordinatorFakes.kt", "MaccmsOfflineProbeExtension.kt",
    ]]
    sources += [net / f"{name}.kt" for name in [
        "DomainIp", "MaccmsPinnedTransport", "MaccmsSpeedTester", "CompletedSpeedTestPhases",
        "StartupSpeedTestGate", "StartupSpeedTestRequest", "SpeedTestRunPolicy", "FullSpeedTestRequest",
        "PlatformStartupStatus", "PlatformSpeedTestCoordinator", "PlatformSpeedTestReport",
        "SpeedTestTimings", "SpeedTestTimingLog", "SpeedTestUiTextPolicy", "ImageSourceDisplayPolicy",
        "ImgDomainPolicy",
    ]]
    sources += tests
    for path in sources:
        if not path.is_file():
            raise RuntimeError(f"Missing coordinator source: {path}")
    selectors = []
    for path in tests:
        current_class = None
        for match in re.finditer(r"\bclass\s+(\w+)|@Test\s+fun\s+`([^`]+)`", path.read_text()):
            if match.group(1):
                current_class = match.group(1)
            else:
                assert current_class, path
                selectors.append(f"com.zycomic.app.net.{current_class}#{match.group(2)}")
    assert len(selectors) == 36, f"Expected all 36 coordinator and run-policy contract methods, found {len(selectors)}"
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
    with tempfile.TemporaryDirectory(prefix="zycomic-coordinator-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        print(f"Compiling {len(sources)} production/fake/test sources", flush=True)
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", str(classes), *map(str, sources),
        ], cwd=ROOT, check=True)
        extension = classes / "META-INF/services/org.junit.jupiter.api.extension.Extension"
        extension.parent.mkdir(parents=True)
        extension.write_text("com.zycomic.app.net.MaccmsOfflineProbeExtension\n")
        for selector in selectors:
            print("FRESH TEST JVM:", selector, flush=True)
            subprocess.run([
                "java", "-Djunit.jupiter.extensions.autodetection.enabled=true", "-jar", junit,
                "execute", "--class-path", str(classes) + os.pathsep + classpath,
                "--select-method=" + selector, "--disable-banner", "--details=summary", "--fail-if-no-tests",
            ], cwd=ROOT, check=True)
    print(f"PASS: {len(selectors)} coordinator runtime methods; each ran in a fresh JVM", flush=True)


if __name__ == "__main__":
    main()
