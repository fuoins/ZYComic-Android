package com.zycomic.app.data.repository

import android.content.Context
import com.zycomic.app.data.dto.BlacklistedBook
import com.zycomic.app.data.dto.BookBlacklistRequest
import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 漫画屏蔽（book blacklist）仓库。
 *
 * 接口（均由抓包逐字核实）：
 * - 列表：`GET /api/users/getBlacklist`，双层 data：`data.data.blacklisted_books[]`
 *   （`{id, book_name, cover_url}`），与屏蔽标签在同一个响应里；
 * - 屏蔽：**`POST /api/classes/updateBookBlacklist`，body `{"bookId":123836}`**。
 *
 * 最关键的一点：这个接口是**开关（toggle）**而不是"添加"。同一 body 连调两次，
 * 第一次返回 `is_blacklisted:true`（已加入屏蔽），第二次返回 `is_blacklisted:false`
 * （已从屏蔽中移除）。因此所有调用都必须以响应的 [ToggleResult.isBlacklisted] 为准反馈结果，
 * 任何"点了就是屏蔽了"的假设都可能在用户已经屏蔽过时造成反向操作。
 *
 * 另外：屏蔽/取消屏蔽都是**账号级**行为（依赖 cookie 里的 uid/PHPSESSID），
 * 所以入口与收藏、标签屏蔽一样必须过登录检查。
 */
object BookBlockRepository {

    private val api get() = NetworkModule.api

    private const val PREFS = "zycomic_book_block"
    private const val KEY_FILTER = "filter_blocked_books"

    /** 已屏蔽漫画（对外视图，字段名收敛成本地用得到的三项）。 */
    data class BlockedBook(val id: Long, val name: String, val coverUrl: String)

    /** toggle 结果：[isBlacklisted] 为服务端返回的新状态，[message] 是服务端文案。 */
    data class ToggleResult(val isBlacklisted: Boolean, val message: String)

    // ==================== 列表 ====================

    /**
     * 获取已屏蔽漫画列表。需要登录。
     *
     * 服务端 id 字段可能是数字也可能是字符串，这里统一用 [StringOrLong] 收敛成 Long，
     * 拿不到合法数字的条目直接丢弃（无法对它做取消操作，留着也没用）。
     */
    suspend fun getBlockedBooks(): List<BlockedBook> {
        checkLoggedIn()
        val resp = api.getBlacklist()
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取屏蔽漫画列表失败" })
        return resp.data?.data?.blacklistedBooks.orEmpty()
            .mapNotNull { it.toBlockedBookOrNull() }
    }

    private fun BlacklistedBook.toBlockedBookOrNull(): BlockedBook? {
        val num = id.trim().toLongOrNull() ?: return null
        return BlockedBook(id = num, name = bookName, coverUrl = coverUrl)
    }

    // ==================== 开关（屏蔽 / 取消屏蔽）====================

    /**
     * 对一本漫画执行服务端 toggle。返回服务端给出的新状态。
     *
     * @throws NotLoggedInException 未登录
     * @throws IOException 服务端 code != 1 或 data 缺失
     */
    suspend fun toggleBlock(bookId: Long): ToggleResult {
        checkLoggedIn()
        val resp = api.updateBookBlacklist(BookBlacklistRequest(bookId = bookId))
        if (resp.code != 1) {
            throw IOException(resp.msg.ifEmpty { "操作屏蔽漫画失败" })
        }
        val d = resp.data ?: throw IOException("屏蔽操作响应为空")
        return ToggleResult(isBlacklisted = d.isBlacklisted, message = d.message)
    }

    /**
     * 批量取消屏蔽：逐个调 [toggleBlock]（服务端没有批量端点，且它是 toggle，
     * 只能对"当前确实屏蔽着"的 id 调用才会得到"取消"的效果）。
     *
     * 串行而不是并发：这些请求全部落在同一个账号会话上，并发既没有收益，
     * 又会把一个失败淹没在一堆同时返回里。返回 (成功数, 失败数)。
     */
    suspend fun unblockBooks(books: List<BlockedBook>): Pair<Int, Int> {
        if (books.isEmpty()) return 0 to 0
        checkLoggedIn()
        var ok = 0
        var failed = 0
        for (b in books) {
            runCatching { toggleBlock(b.id) }
                .onSuccess { ok++ }
                .onFailure { failed++ }
        }
        return ok to failed
    }

    // ==================== 过滤开关（本地持久化，默认开启）====================

    /**
     * "过滤屏蔽漫画"开关：开启后在分类/搜索/排行里隐藏服务端下发的
     * `name == "已屏蔽此漫画"` 占位卡；关闭则像官方一样把占位卡显示出来。
     * 与"过滤屏蔽标签"是两个独立开关，各管一种占位卡。
     */
    fun isFilterEnabled(): Boolean {
        val ctx = DevConfig.appContext ?: return true
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FILTER, true)
    }

    fun setFilterEnabled(enabled: Boolean) {
        DevConfig.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_FILTER, enabled)?.apply()
    }

    // ==================== 内部检查 ====================

    /** 漫画屏蔽是账号级功能，未登录抛异常，由 UI 层引导登录（与收藏/标签屏蔽一致）。 */
    private fun checkLoggedIn() {
        if (!UserRepository.isLoggedIn) {
            throw NotLoggedInException("需要登录后才能使用漫画屏蔽")
        }
    }
}
