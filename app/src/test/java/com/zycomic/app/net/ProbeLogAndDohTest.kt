package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DoH 应答解析（纯函数）。
 *
 * 用途：章节接口/Trello 运行时学到的图源不在 ruledns 的 IP 组里，走 Dns.SYSTEM，
 * 拿不到 IP 分数。为了让"照着日志人工补配置"这件事可执行，测活日志必须带上这些域
 * 实际解析到的 IP —— 这样看到日志就能直接往 doh.json 里写 rule 条目。
 */
class DohResolverTest {

    @Test
    fun `keeps only A records and drops cname`() {
        val json = """
            {"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false,
             "Question":{"name":"newimg9.cc.","type":1},
             "Answer":[{"name":"newimg9.cc.","TTL":300,"type":1,"data":"104.21.19.40"},
                       {"name":"newimg9.cc.","TTL":60,"type":5,"data":"alias.cc."},
                       {"name":"newimg9.cc.","TTL":300,"type":1,"data":"172.67.154.251"}]}
        """.trimIndent()

        assertEquals(listOf("104.21.19.40", "172.67.154.251"), DohResolver.parseARecords(json))
    }

    @Test
    fun `returns empty when there is no answer array`() {
        val json = """{"Status":3,"Question":{"name":"gone.cc.","type":1}}"""
        assertTrue(DohResolver.parseARecords(json).isEmpty())
    }

    @Test
    fun `ignores malformed or non-ipv4 data and keeps order`() {
        val json = """
            {"Answer":[{"type":1,"data":"1.1.1.1"},
                       {"type":1,"data":"not-an-ip"},
                       {"type":1,"data":"300.1.1.1"},
                       {"type":1,"data":""},
                       {"type":1,"data":"9.9.9.9"}]}
        """.trimIndent()

        assertEquals(listOf("1.1.1.1", "9.9.9.9"), DohResolver.parseARecords(json))
    }

    @Test
    fun `garbage input degrades to empty instead of throwing`() {
        assertTrue(DohResolver.parseARecords("{ this is not json").isEmpty())
        assertTrue(DohResolver.parseARecords("").isEmpty())
    }

    @Test
    fun `aaaa records are not mixed into the v4 list`() {
        val json = """
            {"Answer":[{"type":28,"data":"2606:4700::6813:1328"},{"type":1,"data":"104.21.19.40"}]}
        """.trimIndent()

        assertEquals(listOf("104.21.19.40"), DohResolver.parseARecords(json))
    }
}

/**
 * 测活/测速日志行的字段拼装（纯函数）。
 * 日志是用户手工更新 doh.json 的唯一输入，所以字段必须能直接映射到配置：
 * host / 是否有 IP 组 / 实际拨号的 IP / 结论 / httpCode / 延迟。
 */
class ProbeLogTest {

    @Test
    fun `line carries every field needed to edit the config`() {
        val line = ProbeLog.line(
            host = "newmwtuyuan1.cc",
            inRule = true,
            dialedIp = "104.238.220.203",
            verdict = ImgVerdict.OK,
            httpCode = 206,
            delayMs = 83L,
        )

        assertEquals(
            "probe host=newmwtuyuan1.cc inRule=1 ip=104.238.220.203 verdict=OK code=206 delay=83ms",
            line,
        )
    }

    @Test
    fun `host without an ip group is marked so it can be added to the config`() {
        val line = ProbeLog.line(
            host = "learned-from-chapter.cc",
            inRule = false,
            dialedIp = null,
            verdict = ImgVerdict.NO_IMAGE,
            httpCode = 302,
            delayMs = 120L,
        )

        assertTrue(line.contains("inRule=0"))
        assertTrue(line.contains("ip=-"))
        assertTrue(line.contains("verdict=NO_IMAGE"))
    }

    @Test
    fun `unreachable has no code and no delay`() {
        val line = ProbeLog.line(
            host = "dead.cc",
            inRule = true,
            dialedIp = "1.2.3.4",
            verdict = ImgVerdict.UNREACHABLE,
            httpCode = -1,
            delayMs = Long.MAX_VALUE,
        )

        assertTrue(line.contains("verdict=UNREACHABLE"))
        assertTrue(line.contains("code=-"))
        assertTrue(line.contains("delay=>timeout"))
    }
}
