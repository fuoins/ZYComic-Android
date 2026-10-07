package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.BatchFavoriteRequest
import com.zycomic.app.data.dto.FavoriteFolderRequest
import com.zycomic.app.data.dto.FavoriteItem
import com.zycomic.app.data.dto.FavoriteRequest
import com.zycomic.app.data.dto.FavoriteLimitInfo
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.net.NetworkModule
import java.io.IOException

object FavoriteRepository {
    var limitInfo: FavoriteLimitInfo? by androidx.compose.runtime.mutableStateOf<FavoriteLimitInfo?>(null)
        private set

    private val api get() = NetworkModule.api

    // ==================== 收藏列表 ====================

    /**
     * 收藏列表。
     * @param order 1=更新时间, 2=收藏时间（反直觉）；默认 2=收藏时间
     * @param orderType 0=降序, 1=升序
     * @param folderId 分类文件夹 ID，0=全部
     * @param isEnd -1=全部, 0=连载, 1=完结
     * @param isFullVersion -1=全部, 1=高清, 2=清水版, 3=未删减, 4=完整版
     * @param showOnlyUpdated -1=全部, 1=只显示更新
     */
    suspend fun getFavorites(
        page: Int,
        order: Int = 2,
        orderType: Int = 0,
        folderId: Int = 0,
        isEnd: Int = -1,
        isFullVersion: Int = -1,
        showOnlyUpdated: Int = -1,
    ): List<FavoriteItem> {
        checkLoggedIn()
        val resp = api.favorites(
            page = page,
            order = order,
            orderType = orderType,
            folderId = folderId,
            isEnd = isEnd,
            isFullVersion = isFullVersion,
            showOnlyUpdated = showOnlyUpdated,
        )
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取收藏列表失败" })
        limitInfo = resp.data?.favorite_limit_info
        return resp.data?.list ?: emptyList()
    }

    // ==================== 单本收藏操作 ====================

    /** 添加收藏：val=0。 */
    suspend fun addFavorite(bookId: Int, folderId: Int = 0) {
        checkLoggedIn()
        val resp = api.favorite(FavoriteRequest(`val` = 0, bookId = bookId, folderId = folderId))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "收藏失败" })
    }

    /** 取消收藏：val=1 + action=del。 */
    suspend fun removeFavorite(bookId: Int) {
        checkLoggedIn()
        val resp = api.favorite(FavoriteRequest(`val` = 1, bookId = bookId, action = "del"))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "取消收藏失败" })
    }

    // ==================== 批量操作 ====================

    /** 批量取消收藏。ids 为逗号分隔的 book_id 字符串。 */
    suspend fun batchRemove(ids: String) {
        checkLoggedIn()
        val resp = api.batchFavorite(BatchFavoriteRequest(ids = ids, action = "del"))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "批量取消收藏失败" })
    }

    // ==================== 收藏分类文件夹 ====================

    /** 收藏夹列表。 */
    suspend fun getFolderList(): List<Folder> {
        checkLoggedIn()
        val resp = api.folderList()
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取收藏夹列表失败" })
        return resp.data?.list ?: emptyList()
    }

    /** 创建收藏夹：action=moveToFolder，只传 folder_name。 */
    suspend fun createFolder(name: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "moveToFolder", folderName = name),
        )
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "创建收藏夹失败" })
    }

    /** 重命名收藏夹：action=renameFolder，folder_id + folder_name。 */
    suspend fun renameFolder(folderId: String, name: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "renameFolder", folderId = folderId, folderName = name),
        )
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "重命名收藏夹失败" })
    }

    /** 删除收藏夹：action=delFolder，folder_id。 */
    suspend fun deleteFolder(folderId: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "delFolder", folderId = folderId),
        )
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "删除收藏夹失败" })
    }

    /**
     * 移入收藏夹：循环调用 POST api/detail/favorite（val=0, book_id, folder_id），每次一本。
     * 抓包确认移动收藏夹用此接口，不是 api/users/favorite_folder。
     */
    suspend fun moveToFolder(bookIds: List<String>, folderId: Int) {
        checkLoggedIn()
        bookIds.forEach { id ->
            val bookIdInt = id.toIntOrNull() ?: return@forEach
            val resp = api.favorite(FavoriteRequest(`val` = 0, bookId = bookIdInt, folderId = folderId))
            if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "移动到收藏夹失败" })
        }
    }

    /**
     * 移出收藏夹（移到全部收藏）：循环调用 POST api/detail/favorite（val=0, book_id, folder_id=0），每次一本。
     */
    suspend fun moveOutFolder(bookIds: List<String>) {
        checkLoggedIn()
        bookIds.forEach { id ->
            val bookIdInt = id.toIntOrNull() ?: return@forEach
            val resp = api.favorite(FavoriteRequest(`val` = 0, bookId = bookIdInt, folderId = 0))
            if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "移出收藏夹失败" })
        }
    }

    // ==================== 内部检查 ====================

    /** 收藏功能必须检查登录状态，未登录抛出异常。 */
    private fun checkLoggedIn() {
        if (!UserRepository.isLoggedIn) {
            throw NotLoggedInException("需要登录后才能使用收藏功能")
        }
    }
}

/** 未登录异常：UI 层捕获后提示用户登录。 */
class NotLoggedInException(message: String) : IOException(message)
