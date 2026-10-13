package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IP 层测速规划的纯逻辑测试（第二批的主体）。
 *
 * 核心思路：图源 103 个域名其实只落在 15 个 IP 上，所以
 * 「按 IP 测一次 + 把分数派生给所有含该 IP 的域名」比「逐域各测一次」既便宜又准。
 * 这里只测纯计算，不含任何网络与 Android 依赖。
 */
class IpProbePlannerTest {

    private val rule = mapOf(
        "a.cc" to listOf("1.1.1.1", "2.2.2.2"),
        "b.cc" to listOf("2.2.2.2", "3.3.3.3"),
        "c.cc" to listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"),
        "d.cc" to listOf("5.5.5.5"),
        "norule.cc" to emptyList(),
    )
    private val hosts = listOf("a.cc", "b.cc", "c.cc", "d.cc", "norule.cc")

    @Test
    fun `ip union keeps first-seen order and drops duplicates`() {
        assertEquals(
            listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5"),
            IpProbePlanner.uniqueIps(hosts, rule),
        )
    }

    @Test
    fun `representatives are taken in host list order and capped`() {
        val reps = IpProbePlanner.representativesFor("2.2.2.2", hosts, rule, maxRepresentatives = 3)
        assertEquals(listOf("a.cc", "b.cc", "c.cc"), reps)
    }

    @Test
    fun `full probe plans every ip with its representatives`() {
        val plan = IpProbePlanner.plan(hosts, rule, knownScores = emptyMap(), forceFullProbe = true)
        assertEquals(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5"), plan.map { it.ip })
        // norule.cc 没有 IP 组，不该成为任何目标的代表域
        assertTrue(plan.all { t -> t.representatives.none { it == "norule.cc" } })
    }

    @Test
    fun `incremental probe only targets ips that have no score yet`() {
        val known = mapOf("1.1.1.1" to 80L, "2.2.2.2" to 90L)
        val plan = IpProbePlanner.plan(hosts, rule, knownScores = known, forceFullProbe = false)
        assertEquals(listOf("3.3.3.3", "4.4.4.4", "5.5.5.5"), plan.map { it.ip })
    }

    @Test
    fun `domain score is the min of its measured ips and ignores unmeasured ones`() {
        val scores = mapOf("2.2.2.2" to 400L, "3.3.3.3" to 120L)
        // c.cc 有 4 个 IP，其中 1.1.1.1 / 4.4.4.4 未测：不参与 min
        assertEquals(120L, IpProbePlanner.scoreForDomain(rule.getValue("c.cc"), scores))
    }

    @Test
    fun `domain with nothing measured is unavailable not zero`() {
        assertEquals(
            IpProbePlanner.UNAVAILABLE,
            IpProbePlanner.scoreForDomain(listOf("9.9.9.9"), mapOf("1.1.1.1" to 50L)),
        )
    }

    @Test
    fun `domain delays can be derived for every host without any per-domain probe`() {
        val scores = mapOf("1.1.1.1" to 300L, "2.2.2.2" to 100L, "3.3.3.3" to 50L, "5.5.5.5" to 20L)
        val delays = IpProbePlanner.domainDelays(hosts, rule, scores)
        assertEquals(100L, delays["a.cc"])
        assertEquals(50L, delays["b.cc"])
        assertEquals(50L, delays["c.cc"])
        assertEquals(20L, delays["d.cc"])
        assertEquals(IpProbePlanner.UNAVAILABLE, delays["norule.cc"])
    }

    @Test
    fun `scores for ips that left the config are pruned`() {
        val pruned = IpProbePlanner.prune(mapOf("1.1.1.1" to 80L, "8.8.8.8" to 10L), IpProbePlanner.uniqueIps(hosts, rule))
        assertEquals(setOf("1.1.1.1"), pruned.keys)
    }

    @Test
    fun `hosts without an ip group are reported separately`() {
        assertEquals(listOf("norule.cc"), IpProbePlanner.hostsWithoutRule(hosts, rule))
    }

    @Test
    fun `cloudflare anycast ranges are recognised`() {
        // 线上配置里 15 个图源 IP 有 5 个落在 CF 段：104.21.x / 172.65.x / 172.67.x
        assertTrue(IpProbePlanner.isCloudflareAnycast("104.21.19.40"))
        assertTrue(IpProbePlanner.isCloudflareAnycast("104.21.72.208"))
        assertTrue(IpProbePlanner.isCloudflareAnycast("172.65.211.209"))
        assertTrue(IpProbePlanner.isCloudflareAnycast("172.67.154.251"))
        assertTrue(IpProbePlanner.isCloudflareAnycast("172.67.184.246"))
        // 自建源站系不属于 CF
        assertFalse(IpProbePlanner.isCloudflareAnycast("104.238.220.203"))
        assertFalse(IpProbePlanner.isCloudflareAnycast("172.93.103.134"))
        assertFalse(IpProbePlanner.isCloudflareAnycast("207.57.165.154"))
        assertFalse(IpProbePlanner.isCloudflareAnycast("not-an-ip"))
    }

    @Test
    fun `ip order puts the fastest first and keeps original order on ties`() {
        val scores = mapOf("2.2.2.2" to 50L, "1.1.1.1" to 300L)
        assertEquals(listOf("2.2.2.2", "1.1.1.1"), IpProbePlanner.orderIps(listOf("1.1.1.1", "2.2.2.2"), scores))
        // 未测的 IP 排在**所有已测 IP 之后**且不被丢弃（连不上时仍可按序回退）
        assertEquals(
            listOf("2.2.2.2", "1.1.1.1", "3.3.3.3"),
            IpProbePlanner.orderIps(listOf("1.1.1.1", "3.3.3.3", "2.2.2.2"), scores),
        )
        // 一个都没测过时保持原序，不产生随机重排
        assertEquals(
            listOf("1.1.1.1", "3.3.3.3", "2.2.2.2"),
            IpProbePlanner.orderIps(listOf("1.1.1.1", "3.3.3.3", "2.2.2.2"), emptyMap()),
        )
    }
}

/**
 * IP 分数表的存储与失效判定（纯逻辑，落盘由调用方负责）。
 *
 * 必须失效的情形只有三种：网络身份变了、直连策略翻了（VPN 下走系统 DNS，分数无意义）、
 * 超过新鲜期。**配置里 IP 集合变化不在这里判**——那由 planner 的增量探测天然覆盖。
 */
class IpScoreStoreTest {

    @Test
    fun `round trip through the persisted string form`() {
        val store = IpScoreStore()
        store.put("1.1.1.1", 80L)
        store.put("2.2.2.2", IpScoreStore.UNAVAILABLE)
        store.networkKey = "wifi-abc|direct"
        store.directStrategy = true
        store.savedAt = 1_000L

        val restored = IpScoreStore.fromPersistentString(store.toPersistentString())

        assertEquals(mapOf("1.1.1.1" to 80L, "2.2.2.2" to IpScoreStore.UNAVAILABLE), restored.scores)
        assertEquals("wifi-abc|direct", restored.networkKey)
        assertEquals(true, restored.directStrategy)
        assertEquals(1_000L, restored.savedAt)
    }

    @Test
    fun `scores stay valid for the same network and strategy within freshness`() {
        val store = IpScoreStore()
        store.put("1.1.1.1", 80L)
        store.networkKey = "netA"
        store.directStrategy = true
        store.savedAt = 1_000L

        assertTrue(store.isValidFor("netA", directStrategy = true, now = 1_000L + 60_000L, ttlMs = 6 * 60 * 60 * 1000L))
    }

    @Test
    fun `scores are invalid when the network identity changes`() {
        val store = IpScoreStore()
        store.put("1.1.1.1", 80L)
        store.networkKey = "netA"
        store.directStrategy = true
        store.savedAt = 1_000L

        assertFalse(store.isValidFor("netB", directStrategy = true, now = 2_000L, ttlMs = 60_000L))
    }

    @Test
    fun `scores are invalid when the transport strategy flips to vpn`() {
        val store = IpScoreStore()
        store.put("1.1.1.1", 80L)
        store.networkKey = "netA"
        store.directStrategy = true
        store.savedAt = 1_000L

        // VPN 模式走 Dns.SYSTEM，硬编码 IP 顺序根本不参与，旧分数不能接着用
        assertFalse(store.isValidFor("netA", directStrategy = false, now = 2_000L, ttlMs = 60_000L))
    }

    @Test
    fun `scores expire after the freshness window`() {
        val store = IpScoreStore()
        store.put("1.1.1.1", 80L)
        store.networkKey = "netA"
        store.directStrategy = true
        store.savedAt = 0L

        assertFalse(store.isValidFor("netA", directStrategy = true, now = 61_000L, ttlMs = 60_000L))
    }

    @Test
    fun `empty store is never valid`() {
        val store = IpScoreStore()
        store.networkKey = "netA"
        store.directStrategy = true
        store.savedAt = 1_000L

        assertFalse(store.isValidFor("netA", directStrategy = true, now = 1_001L, ttlMs = 60_000L))
    }

    @Test
    fun `garbage persisted string degrades to an empty store instead of throwing`() {
        val restored = IpScoreStore.fromPersistentString("###not-a-store###")
        assertTrue(restored.scores.isEmpty())
    }
}
