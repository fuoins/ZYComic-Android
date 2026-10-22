#!/usr/bin/env python3
"""Run real retry/pagination/session Kotlin tests without an Android SDK.

Only DTOs, unused API methods and filter switches are stubbed. The retry,
transport classification, pagination and session policy are production code.
"""
from pathlib import Path
import os
import subprocess
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = Path('/tmp/manwa-first-page-retry-tests')
DEPS = ROOT / 'tools/offline-tests/build/deps'
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'


def jar(group, name, version):
    prepared = DEPS / f'{name}-{version}.jar'
    if prepared.is_file():
        return str(prepared)
    matches = sorted((CACHE / group / name / version).rglob('*.jar'))
    if not matches:
        raise RuntimeError(f'Missing {group}:{name}:{version}; run prepareHarnessDependencies first')
    return str(matches[0])


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    stubs = {
        'Models.kt': '''package com.zycomic.app.data.dto
 data class Manga(val id: String = "", val name: String = "")
 data class TagItem(val name: String)
 data class Response<T>(val code: Int = 1, val msg: String = "", val data: T? = null, val nums: Int? = null)
 data class ListData(val list: List<Manga> = emptyList())
''',
        'Api.kt': '''package com.zycomic.app.net
 import com.zycomic.app.data.dto.*
 object NetworkModule { val api = Api() }
 class Api {
 suspend fun index(): Response<Manga> = error("unexpected API call")
 suspend fun rank(type: Int, page: Int): Response<ListData> = error("unexpected API call")
 suspend fun newest(page: Int, size: Int, date: String): Response<ListData> = error("unexpected API call")
 suspend fun detail(bookId: String): Response<Manga> = error("unexpected API call")
 suspend fun search(keyword: String, page: Int): Response<ListData> = error("unexpected API call")
 suspend fun classes(page: Int, gender: Int, tag: String, area: Int, end: Int, st: Int): Response<ListData> = error("unexpected API call")
 }
''',
        'Filters.kt': '''package com.zycomic.app.data.repository
 object TagRepository { fun isFilterEnabled() = true }
 object BookBlockRepository { fun isFilterEnabled() = true }
''',
        'Tags.kt': '''package com.zycomic.app.data
 object AllTags { val LIST = emptyList<String>() }
''',
    }
    sources = []
    for name, text in stubs.items():
        path = OUT / name
        path.write_text(text)
        sources.append(path)
    base = ROOT / 'app/src/main/java/com/zycomic/app'
    tests = ROOT / 'app/src/test/java/com/zycomic/app'
    sources += [
        base / 'ui/browse/ManwaFirstPageRetry.kt',
        base / 'net/RuntimeRecoveryPolicy.kt',
        base / 'data/repository/MangaRepository.kt',
        base / 'data/repository/ManwaSessionVerification.kt',
        tests / 'ui/browse/ManwaFirstPageRetryTest.kt',
        tests / 'data/repository/ManwaBrowsePaginationTest.kt',
        tests / 'data/repository/ManwaSessionVerificationPolicyTest.kt',
    ]
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21')
    coroutine = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler = ':'.join([
        jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'), stdlib,
        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
        jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.21'),
        coroutine, annotations,
    ])
    junit = jar('org.junit.platform', 'junit-platform-console-standalone', '1.10.2')
    cp = ':'.join([stdlib, coroutine, annotations, junit])
    classes = OUT / 'classes'
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(exist_ok=True)
    subprocess.run([
        'java', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp,
        '-d', str(classes), *map(str, sources),
    ], cwd=ROOT, check=True)
    subprocess.run([
        'java', '-jar', junit, 'execute', '--class-path', f'{classes}:{cp}',
        '--scan-class-path', '--disable-banner', '--fail-if-no-tests',
        '--reports-dir', str(OUT / 'reports'),
    ], cwd=ROOT, check=True)
    vm = (base / 'ui/browse/BrowseViewModel.kt').read_text()
    load = vm.split('private suspend fun doLoad(', 1)[1].split('private suspend fun doLoadJm', 1)[0]
    assert 'ManwaFirstPageRetry(enabled = reset' in load
    assert load.count('MangaRepository.getNewest(') == 1, 'reuse first-page total; no second newest read'
    for guard in ['UserRepository.isCurrentBrowseSession(session)', 'RuntimeNetworkGate.revision == networkRevision',
                  'RouteManager.configRevision == configRevision', 'tabs.getOrNull(tab) == item',
                  'mainTab.value == tab', 'browseVisible']:
        assert guard in load, guard
    assert load.index('catch (e: CancellationException)') < load.index('catch (e: Exception)')
    reset = load.split('if (reset) {', 1)[1].split('} else {', 1)[0]
    assert reset.index('ts.loaded = false') < reset.index('ts.mangas.value = emptyList()')
    assert 'private var browseVisible = false' in vm
    assert 'if (!browseVisible) return' in vm
    assert '!state.loaded && !state.loading.value && state.error.value == null' in vm

    screen = (base / 'ui/browse/BrowseScreen.kt').read_text()
    assert 'onDispose { vm.onBrowseHidden() }' in screen
    network = (base / 'net/NetworkModule.kt').read_text()
    api_client = network.split('private fun buildApiClient()', 1)[1].split('private fun buildImageClient()', 1)[0]
    assert '.retryOnConnectionFailure(false)' in api_client
    print('UI wiring, snapshot guards, latest-total reuse and unchanged global retry safety: PASS')


if __name__ == '__main__':
    main()
