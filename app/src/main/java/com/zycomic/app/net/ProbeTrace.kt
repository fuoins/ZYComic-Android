package com.zycomic.app.net

/**
 * 一次探测的观测数据：拨的是哪台机器、这一发有没有在客户端里排队。
 *
 * 为什么必须有它：
 * 1. `RuleDns` 返回一串 IP，OkHttp 按顺序试到第一个连得上的 —— 所以"这个域名慢"到底是
 *    "它拨了哪台机器"还是"这个 vhost 自己慢"，光看总延迟分不出来。测速/测活都是**构造时
 *    就把 IP 钉死**的（见 `NetworkModule.newPinnedIpProbeClient`），所以这个值确定可得，
 *    人工更新 ruledns 时才看得到"域名 -> 实际机器"的对应关系。
 * 2. 并发探测超出客户端上限时，等槽位的时间会混进延迟（`probe()` 的计时起点在 `execute()`
 *    之前），排在后面的目标会被系统性测慢。排队时间在**自己的并发闸门**上量，-1 表示没等待。
 *
 * 只保留填得上、说得清的字段；曾经设想的 DNS/TLS 分段耗时与"是否复用连接"要依赖
 * `EventListener` 的回调签名，拿不到确切签名就不写空壳字段 —— 留一个永远 -1 的字段
 * 比没有更坏，会让人以为" tls 没耗时"。
 */
class ProbeTrace(
    val host: String,
) {
    /** 本次实际钉住/拨出的地址，形如 `104.238.220.203:443` 或 `[2606::1]:443`。 */
    @Volatile var dialedAddress: String? = null

    /** 在并发闸门上等待的毫秒数；-1 = 没有等待记录（不要把 0 当"未记录"，0 是合法值）。 */
    @Volatile var queueWaitMs: Long = -1L

    /** 拨到的裸 IP（去掉端口）；未记录时为 null。 */
    val dialedIp: String?
        get() {
            val raw = dialedAddress ?: return null
            // IPv6 形态 [..]:port 只能按最后一个冒号切；IPv4 直接取最后一段
            return if (raw.startsWith('[')) raw.substringBeforeLast(':')
            else raw.substringBeforeLast(':', raw)
        }

    /** 是否在这发之前排过队（等槽位/等线程）。 */
    val waitedForSlot: Boolean
        get() = queueWaitMs > 0L
}
