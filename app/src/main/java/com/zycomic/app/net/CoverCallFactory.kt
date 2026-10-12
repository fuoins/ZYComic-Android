package com.zycomic.app.net

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 封面 Coil 的统一 Call.Factory：始终委托"当前的"[NetworkModule.imageClient]。
 *
 * 为什么需要它：Coil 的 ImageLoader 是全局单例，若像过去那样在首次加载时固化一个
 * OkHttpClient（komikku 的 NetworkHelper.client），后续网络/VPN 变化触发的
 * [NetworkModule.rebuildClient] 无法影响封面链路——封面会一直用旧的 DNS/SNI/连接池，
 * 也拿不到 ImageInterceptor 的图片头、最快图域改写与解密。
 *
 * 这里每次 [rebind] 用最新 imageClient 派生一个 client（newBuilder 共享其连接池/DNS/Dispatcher/
 * cookieJar/拦截器，成本极低）；网络重建后调用 [rebind] 即可让封面立即跟随新网络栈。
 */
object CoverCallFactory : Call.Factory {

    @Volatile
    private var current: OkHttpClient? = null

    /** 用最新的 imageClient 重新派生封面 client（rebuildClient 后调用）。 */
    fun rebind() {
        current = NetworkModule.imageClient.newBuilder().build()
    }

    override fun newCall(request: Request): Call {
        val client = current ?: synchronized(this) {
            current ?: NetworkModule.imageClient.newBuilder().build().also { current = it }
        }
        return client.newCall(request)
    }
}
