package com.zycomic.app.net

import okhttp3.Dns
import java.net.InetAddress

/**
 * 自定义 DNS：根据 [RouteManager.resolveIp] 的 rule 配置，将域名解析为预设 IP 列表。
 *
 * 行为：
 * - 若 hostname 在 customRule/STATIC_IP 中有映射，则返回对应的 InetAddress 列表。
 * - 否则走系统 DNS [Dns.SYSTEM]。
 *
 * 这是方案B的核心：OkHttp 在连接前先通过此 Dns 接口解析域名，
 * 直接拿到 IP 列表后进行连接，无需再用 Interceptor 重写 URL。
 */
object ManwaDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val ips = RouteManager.resolveIp(hostname)
        if (ips.isNotEmpty()) {
            // 遍历每个 IP，解析为 InetAddress
            return ips.flatMap { ip ->
                InetAddress.getAllByName(ip).toList()
            }
        }
        // 无预设，走系统 DNS
        return Dns.SYSTEM.lookup(hostname)
    }
}
