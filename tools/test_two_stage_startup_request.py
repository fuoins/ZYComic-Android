#!/usr/bin/env python3
"""Run the production foreground/background request owner without Android or network."""
from pathlib import Path
import os
import subprocess
import tempfile
from test_adaptive_speed_probe import jar

ROOT = Path(__file__).resolve().parents[1]


def main():
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
    classpath = os.pathsep.join([stdlib, coroutines, junit, jar("org.jetbrains", "annotations", "13.0")])
    names = ["StartupSpeedTestRequest", "FullSpeedTestRequest"]
    sources = [ROOT / f"app/src/main/java/com/zycomic/app/net/{name}.kt" for name in names]
    sources += [ROOT / f"app/src/test/java/com/zycomic/app/net/{name}Test.kt" for name in names]
    sources += [ROOT / "app/src/test/java/com/zycomic/app/ui/StartupUiContractTest.kt"]
    with tempfile.TemporaryDirectory(prefix="two-stage-startup-") as temp:
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", temp, *map(str, sources),
        ], cwd=ROOT, check=True)
        subprocess.run([
            "java", "-jar", junit, "execute", "--class-path", temp + os.pathsep + classpath,
            "--scan-class-path", "--disable-banner", "--details=summary", "--fail-if-no-tests",
        ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
