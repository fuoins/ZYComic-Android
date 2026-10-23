#!/usr/bin/env python3
"""Run real body-sampling and response-validation tests without the Android SDK."""
from pathlib import Path
import os
import subprocess
import tempfile
from test_adaptive_speed_probe import jar, ROOT


def main() -> None:
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
        stdlib, junit, jar("org.jetbrains", "annotations", "13.0"),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.6.1"),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.6.1"),
    ])
    names = ["BoundedImageProbeSampler", "JmSpeedProbeProtocol", "SpeedProbeResponseValidation"]
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    tests = ROOT / "app/src/test/java/com/zycomic/app/net"
    sources = [net / f"{name}.kt" for name in names] + [tests / f"{name}Test.kt" for name in names]
    with tempfile.TemporaryDirectory(prefix="speed-probe-protocol-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", str(classes), *map(str, sources),
        ], cwd=ROOT, check=True)
        selectors = [arg for name in names for arg in ("--select-class", f"com.zycomic.app.net.{name}Test")]
        subprocess.run([
            "java", "-jar", junit, "execute", "--class-path", str(classes) + os.pathsep + classpath,
            *selectors, "--disable-banner", "--details=summary", "--fail-if-no-tests",
        ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
