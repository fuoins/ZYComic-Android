package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * DNS 覆盖拦截器：根据 [RouteManager.resolveIp] 的 rule 配置，将请求域名替换为预设 IP 直连。
 *
 * 行为：
 * - 若请求 host 在 rule/预设表中有 IP 映射，则把 URL 中的 host 替换为该 IP（轮询取一个 IP）。
 * - 保留原始 host 到 Host 请求头，服务端据此路由虚拟主机。
 * - HTTPS 场景下配合 NetworkModule 的 trust-all SSL + 主机名放行，IP 直连可正常握手。
 *
 * 注意：此拦截器必须在 [ManwaInterceptor] / [ImageInterceptor] 之前执行（networkInterceptor 或最早的 interceptor），
 * 确保后续拦截器看到的 URL 已经是目标地址。
 */
class DnsOverrideInterceptor : Interceptor {

    /** 轮询计数器，多 IP 时每次取不同 IP */
    @Volatile private var counter: Long = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host

        val ips = RouteManager.resolveIp(host)
        if (ips.isEmpty()) {
            return chain.proceed(original)
        }

        // 简单轮询：每次偏移取一个 IP
        val idx = (counter++ % ips.size).toInt()
        val ip = ips[idx]

        // 重写 URL：host 替换为 IP，保留 scheme / port / path / query
        val newUrl = original.url.newBuilder()
            .host(ip)
            .build()

        val rewritten = original.newBuilder()
            .url(newUrl)
            // 保留原始 host，供服务端虚拟主机路由 + TLS SNI 参考
            .header("Host", host)
            .build()

        return chain.proceed(rewritten)
    }
}
