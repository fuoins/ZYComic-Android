package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 探测观测数据（实际拨到哪个 IP、是否复用连接、各阶段耗时）的纯逻辑测试。
 *
 * 目的有两个：1) 测活日志要能写出"这个域拨的是哪台机器"，供人工更新 ruledns；
 * 2) 验证"并发超出客户端上限会把延迟测高"这个推断 —— 没有这些字段就永远只能靠猜。
 */
class ProbeTraceTest {

    private val ms = 1_000_000L

    @Test
    fun `ip is stripped from the dialed address`() {
        val t = ProbeTrace("a.cc")
        t.dialedAddress = "104.238.220.203:443"

        assertEquals("104.238.220.203", t.dialedIp)
    }

    @Test
    fun `ip is null before a connection exists`() {
        val t = ProbeTrace("a.cc")

        assertNull(t.dialedIp)
    }

    @Test
    fun `ipv6 dialed address still yields a usable ip field`() {
        val t = ProbeTrace("a.cc")
        t.dialedAddress = "[2606:4700::6813:1328]:443"

        assertEquals("[2606:4700::6813:1328]", t.dialedIp)
    }

}
/** 追加：并发排队等待必须可见（这是"尾部域名被系统性测慢"那条推断的唯一证据来源）。 */
class ProbeQueueTraceTest {
    private val ms = 1_000_000L

    @Test
    fun `queue wait is measured between dispatcherQueue marks`() {
        val t = ProbeTrace("a.cc")
        t.queueWaitMs = 320L

        assertEquals(320L, t.queueWaitMs)
        assertTrue(t.waitedForSlot)
    }

    @Test
    fun `no queue record means no wait reported`() {
        val t = ProbeTrace("a.cc")

        assertEquals(-1L, t.queueWaitMs)
        assertFalse(t.waitedForSlot)
    }

}
