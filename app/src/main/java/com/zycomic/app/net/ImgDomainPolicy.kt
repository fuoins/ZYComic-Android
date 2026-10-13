package com.zycomic.app.net

/**
 * 图源改写目标的优先级决策（纯函数，不依赖 Android / 网络 / 持久化）。
 *
 * 背景 bug：`useFastestImgForAll` 为 true 时，`ImageInterceptor`、`FastestImgInterceptor`、
 * 封面拼 URL、章节拼 URL 全部只读派生出来的 `fastestImgDomain`，而手动切换图源改的是
 * `imgIndex` —— 后者根本不参与改写，所以阅读器/设置页里的"切换图源"对实际请求是空操作。
 *
 * 优先级（已确认）：
 * ```
 * 手动 pin（在列表里才算） > 开关开启时的派生最快域 > null（不接管，用原始域名）
 * ```
 * `null` 的含义必须保留：本轮没有任何可用测速结论时不该接管 URL，否则会把所有图片
 * 强行改写到一个还没验证过的域上。
 */
object ImgDomainPolicy {

    /**
     * @param pinned 用户手动固定的图源域名，null/空白 = 没固定
     * @param imgDomains 当前生效的图源列表，pin 必须落在这里面才算有效
     * @param useFastestImgForAll 「封面和章节用测速最快图源」开关
     * @param derivedFastest 由测速结果派生出的最快域，null = 本轮没有可用结论
     * @return 应当把图片 URL 的 host 改写成哪个图源域名；null = 不改写
     */
    fun rewriteTarget(
        pinned: String?,
        imgDomains: List<String>,
        useFastestImgForAll: Boolean,
        derivedFastest: String?,
    ): String? {
        val trimmed = pinned?.trim()
        if (!trimmed.isNullOrEmpty()) {
            // 大小写不敏感匹配，但返回列表里的原始写法：URL 与缓存键要和其它地方保持一致
            imgDomains.firstOrNull { it.equals(trimmed, ignoreCase = true) }?.let { return it }
        }
        return if (useFastestImgForAll) derivedFastest else null
    }
}
