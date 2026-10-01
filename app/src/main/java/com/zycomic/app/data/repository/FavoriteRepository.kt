package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.BatchFavoriteRequest
import com.zycomic.app.data.dto.FavoriteFolderRequest
import com.zycomic.app.data.dto.FavoriteRequest
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 收藏仓库：收藏列表 / 单本收藏操作 / 批量操作 / 收藏分类文件夹。
 *
 * 注意：
 * - order=1 实际是更新时间排序，order=2 实际是收藏时间排序（反直觉）。
 * - order_type: 0=降序, 1=升序。
 * - 所有收藏操作前必须检查 [UserRepository.isLoggedIn]。
 */
object FavoriteRepository {

    private val api get() = NetworkModule.api

    // ==================== 收藏列表 ====================

    /**
     * 收藏列表。
     * @param order 1=更新时间, 2=收藏时间（反直觉）
     * @param orderType 0=降序, 1=升序
     * @param folderId 分类文件夹 ID，0=全部
     * @param gender 性别筛选，-1=全部
     * @param isFullVersion 0=否, 1=是, -1=全部
     * @param isEnd 0=连载, 1=完结, -1=全部
     * @param showOnlyUpdated 0=否, 1=是, -1=全部
     */
    suspend fun getFavorites(
        page: Int,
        order: Int = 1,
        orderType: Int = 0,
        folderId: Int = 0,
        gender: Int = -1,
        isFullVersion: Int = -1,
        isEnd: Int = -1,
        showOnlyUpdated: Int = -1,
    ): List<Manga> {
        checkLoggedIn()
        val resp = api.favorites(
            page = page,
            order = order,
            orderType = orderType,
            folderId = folderId,
            gender = gender,
            isFullVersion = isFullVersion,
            isEnd = isEnd,
            showOnlyUpdated = showOnlyUpdated,
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取收藏列表失败" })
        return resp.data?.list ?: emptyList()
    }

    // ==================== 单本收藏操作 ====================

    /** 添加收藏。 */
    suspend fun addFavorite(bookId: Int, folderId: Int = 0) {
        checkLoggedIn()
        val resp = api.favorite(FavoriteRequest(`val` = 1, bookId = bookId, folderId = folderId))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "收藏失败" })
    }

    /** 取消收藏。 */
    suspend fun removeFavorite(bookId: Int) {
        checkLoggedIn()
        val resp = api.favorite(FavoriteRequest(`val` = 0, bookId = bookId))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "取消收藏失败" })
    }

    // ==================== 批量操作 ====================

    /** 批量取消收藏。ids 为逗号分隔的 ID 字符串。 */
    suspend fun batchRemove(ids: String) {
        checkLoggedIn()
        val resp = api.batchFavorite(BatchFavoriteRequest(ids = ids, action = "del"))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "批量取消收藏失败" })
    }

    // ==================== 收藏分类文件夹 ====================

    /** 收藏分类列表。 */
    suspend fun getFolderList(): List<Folder> {
        checkLoggedIn()
        val resp = api.folderList()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取分类列表失败" })
        return resp.data
    }

    /** 创建收藏分类。 */
    suspend fun createFolder(name: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "addFolder", newName = name)
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "创建分类失败" })
    }

    /** 重命名收藏分类。 */
    suspend fun renameFolder(folderId: Int, name: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "renameFolder", folderId = folderId, newName = name)
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "重命名分类失败" })
    }

    /** 删除收藏分类。 */
    suspend fun deleteFolder(folderId: Int) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "delFolder", folderId = folderId)
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "删除分类失败" })
    }

    /** 移动漫画到指定分类。ids 为逗号分隔的 ID 字符串。 */
    suspend fun moveToFolder(ids: String, folderId: Int) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "moveToFolder", folderId = folderId)
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "移动到分类失败" })
    }

    /** 移动漫画到全部收藏夹（移出当前分类）。ids 为逗号分隔的 ID 字符串。 */
    suspend fun moveOutFolder(ids: String) {
        checkLoggedIn()
        val resp = api.favoriteFolder(
            FavoriteFolderRequest(action = "moveOutFolder")
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "移出分类失败" })
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
