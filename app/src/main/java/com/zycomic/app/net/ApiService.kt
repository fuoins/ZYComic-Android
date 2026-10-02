package com.zycomic.app.net

import com.zycomic.app.data.dto.AddBlacklistRequest
import com.zycomic.app.data.dto.ApiResponse
import com.zycomic.app.data.dto.BatchFavoriteRequest
import com.zycomic.app.data.dto.BlacklistResponse
import com.zycomic.app.data.dto.ChapterContent
import com.zycomic.app.data.dto.ClassResponse
import com.zycomic.app.data.dto.FavoriteFolderRequest
import com.zycomic.app.data.dto.FavoriteItem
import com.zycomic.app.data.dto.FavoriteRequest
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.data.dto.ListData
import com.zycomic.app.data.dto.LoginRequest
import kotlinx.serialization.json.JsonElement
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.dto.NewestResponse
import com.zycomic.app.data.dto.PointLogResponse
import com.zycomic.app.data.dto.RankResponse
import com.zycomic.app.data.dto.RemoveBlacklistRequest
import com.zycomic.app.data.dto.SearchResponse
import com.zycomic.app.data.dto.User
import com.zycomic.app.data.dto.WelfareResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit 接口定义。
 *
 * 注意：
 * - baseUrl 由 [NetworkModule] 构建时取 [RouteManager.baseUrl]，切换线路需重建 Retrofit。
 * - 通用参数（facility/deviceid/timestamp）和鉴权头（X-Token/devid/UA）由 [ManwaInterceptor] 自动追加。
 * - 响应体由 [ManwaInterceptor] 自动 AES 解密后交给 converter 解析。
 */
interface ApiService {

    // ---------- 首页 ----------
    @GET("api/index/index")
    suspend fun index(): ApiResponse<Manga>

    // ---------- 排行榜 ----------
    // type=0 人气, 1 新番, 2 完结
    @GET("api/rank/index")
    suspend fun rank(
        @Query("type") type: Int,
        @Query("page") page: Int,
    ): RankResponse

    // ---------- 最近更新 ----------
    // nums 在响应顶层；date 格式 YYYY-MM-DD
    @GET("api/newest/index")
    suspend fun newest(
        @Query("page") page: Int,
        @Query("size") size: Int = 30,
        @Query("cid") cid: Int = 0,
        @Query("sort") sort: String = "addtime",
        @Query("state") state: Int = 0,
        @Query("date") date: String,
    ): NewestResponse

    // ---------- 详情 ----------
    // data 直接是漫画对象
    @GET("api/detail/index")
    suspend fun detail(@Query("id") bookId: String): ApiResponse<Manga>

    // ---------- 搜索（参数是 k，不是 keyword） ----------
    @GET("api/search/index")
    suspend fun search(
        @Query("k") keyword: String,
        @Query("page") page: Int,
    ): SearchResponse

    // ---------- 分类 ----------
    // tag 是标签名字符串（逗号分隔），不是数字 ID
    @GET("api/classes/index")
    suspend fun classes(
        @Query("page") page: Int,
        @Query("gender") gender: Int,
        @Query("tag") tag: String = "",
        @Query("area") area: Int = 0,
        @Query("end") end: Int = 0,
        @Query("has_full") hasFull: Int = 0,
        @Query("level") level: Int = 0,
        @Query("st") st: Int = 0,
        @Query("orderBy") orderBy: Int = 0,
    ): ClassResponse

    // ---------- 章节内容（响应解密后为 ChapterContent） ----------
    @GET("api/chapters/index")
    suspend fun chapters(@Query("id") chapterId: String): ApiResponse<ChapterContent>

    // ---------- 用户：登录/注册/登出 ----------
    @POST("api/account/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<User>

    @POST("api/account/register")
    suspend fun register(@Body body: LoginRequest): ApiResponse<User>

    @POST("api/account/logout")
    suspend fun logout(): ApiResponse<JsonElement>

    @GET("api/users/info")
    suspend fun userInfo(): ApiResponse<User>

    // ---------- 收藏列表 ----------
    // order=1 更新时间, order=2 收藏时间（反直觉）; order_type 0=降序 1=升序
    // data={list:[...], limit:N}，由 ListData 包装
    @GET("api/users/favorite")
    suspend fun favorites(
        @Query("page") page: Int,
        @Query("order") order: Int = 2,
        @Query("order_type") orderType: Int = 0,
        @Query("folder_id") folderId: Int = 0,
        @Query("isEnd") isEnd: Int = -1,
        @Query("isFullVersion") isFullVersion: Int = -1,
        @Query("showOnlyUpdated") showOnlyUpdated: Int = -1,
    ): ApiResponse<ListData<FavoriteItem>>

    // ---------- 收藏操作（单本） ----------
    // val=0 收藏(add), val=1 取消收藏(remove)
    @POST("api/detail/favorite")
    suspend fun favorite(@Body body: FavoriteRequest): ApiResponse<JsonElement>

    // ---------- 批量取消收藏 ----------
    // body={"ids":"id1,id2","action":"del"}
    @POST("api/users/favorite")
    suspend fun batchFavorite(@Body body: BatchFavoriteRequest): ApiResponse<JsonElement>

    // ---------- 收藏分类 ----------
    @GET("api/users/folder_list")
    suspend fun folderList(): ApiResponse<ListData<Folder>>

    @POST("api/users/favorite_folder")
    suspend fun favoriteFolder(@Body body: FavoriteFolderRequest): ApiResponse<JsonElement>

    // ---------- 阅读历史 ----------
    @GET("api/users/history")
    suspend fun history(@Query("page") page: Int): ApiResponse<ListData<HistoryItem>>

    // ---------- 删除历史 ----------
    // ids 在 URL query，逗号 %2C 编码，body 为空
    @POST("api/users/history")
    suspend fun deleteHistory(
        @Query("ids") ids: String,
        @Query("action") action: String = "del",
    ): ApiResponse<JsonElement>

    // ---------- 签到福利 ----------
    @GET("api/users/welfare")
    suspend fun welfare(): WelfareResponse

    // ---------- 积分明细 ----------
    @GET("api/users/point_logs")
    suspend fun pointLogs(@Query("page") page: Int): PointLogResponse

    // ---------- 屏蔽标签 ----------
    /** 获取已屏蔽标签列表：响应为双层 data {"data":{"data":{"blacklisted_tags":[...]}}} */
    @GET("api/users/getBlacklist")
    suspend fun getBlacklist(): BlacklistResponse

    /** 添加屏蔽标签：body={"selectedTags":["tag1","tag2"]} */
    @POST("api/classes/addBlacklist")
    suspend fun addBlacklist(@Body body: AddBlacklistRequest): ApiResponse<JsonElement>

    /** 移除屏蔽标签：body={"removeTags":["tag1","tag2"]} */
    @POST("api/classes/removeBlacklist")
    suspend fun removeBlacklist(@Body body: RemoveBlacklistRequest): ApiResponse<JsonElement>
}
