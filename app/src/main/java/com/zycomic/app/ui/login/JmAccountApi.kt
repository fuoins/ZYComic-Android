package com.zycomic.app.ui.login

import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Account endpoints use only the isolated JM client and never persist submitted credentials. */
internal object JmAccountApi {
    suspend fun register(username: String, email: String, password: String, passwordConfirm: String, gender: String): String =
        postForm(
            "/register",
            JmAccountFormPolicy.registrationFields(username, email, password, passwordConfirm, gender),
            secrets = listOf(password, passwordConfirm),
        )

    suspend fun activate(username: String, code: String): String =
        postForm("/confirm", JmAccountFormPolicy.activationFields(username, code), secrets = listOf(code))

    /** The measured resend contract is POST /confirm with username + password (not a separate path). */
    suspend fun resendActivation(username: String, password: String): String =
        postForm("/confirm", JmAccountFormPolicy.resendFields(username, password), secrets = listOf(password))

    /** This sends only the registered email; it does not rename the account or return credentials. */
    suspend fun forgotPassword(email: String): String =
        postForm("/forgot", JmAccountFormPolicy.forgotFields(email))

    suspend fun changePassword(password: String, passwordConfirm: String): String {
        require(password.isNotBlank()) { "请输入新密码" }
        require(password == passwordConfirm) { "两次输入的密码不一致" }
        val uid = JmAuthStore.uid()?.takeIf { it.isNotBlank() && it.all { digit -> digit in '0'..'9' } }
            ?: throw IOException("JM登录资料缺少有效用户ID，请重新登录")
        val baseUrl = requireBaseUrl()
        val secrets = listOf(password, passwordConfirm)

        return withContext(Dispatchers.IO) {
            val preservedFields = loadProfile(baseUrl, uid, secrets)
            val formFields = JmAccountFormPolicy.passwordChangeFields(preservedFields, password, passwordConfirm)
            val updateRequest = Request.Builder()
                .url("$baseUrl/useredit/$uid")
                .post(formBody(formFields))
                .build()
            val response = execute(updateRequest, secrets)
            val apiMessage = response.errorMsg ?: run {
                val bodyRoot = parseObject(response.body, "JM改密响应无法解析", secrets)
                JmAccountFormPolicy.message(
                    status = bodyRoot.optString("status").takeIf(String::isNotBlank),
                    msg = bodyRoot.optString("msg").takeIf(String::isNotBlank),
                    errors = bodyRoot.opt("errors"),
                    errorMsg = optionalMessage(bodyRoot),
                )
            }
            val safeMessage = JmAccountFormPolicy.redactSecrets(apiMessage, secrets)
            if (safeMessage != JmAccountFormPolicy.PROFILE_UPDATE_SUCCESS) {
                if (!response.httpSuccessful) throw IOException(safeMessage.ifBlank { "JM改密失败（HTTP ${response.code}）" })
                throw IOException(safeMessage.ifBlank { "JM改密未确认成功，请稍后重试" })
            }

            val verifiedFields = try {
                loadProfile(baseUrl, uid, secrets)
            } catch (error: IOException) {
                throw IOException("密码提交已确认，但无法核验个人资料是否保持原值；请检查个人资料后再操作", error)
            }
            if (!JmAccountFormPolicy.nonPasswordFieldsPreserved(preservedFields, verifiedFields)) {
                throw IOException("密码提交已确认，但个人资料回读与原值不一致；请检查个人资料后再操作")
            }
            "密码已更新，个人资料已回读核验"
        }
    }

    private fun loadProfile(baseUrl: String, uid: String, secrets: List<String>): Map<String, String?> {
        val request = Request.Builder().url("$baseUrl/useredit/$uid").get().build()
        val response = execute(request, secrets)
        if (!response.httpSuccessful) throw IOException("无法读取JM完整个人资料，已取消改密以保护原资料")
        val root = parseObject(response.body, "JM个人资料响应无法解析", secrets)
        val profile = extractObject(root, "JM个人资料响应缺少资料对象", secrets)
        return JmAccountFormPolicy.profileFieldNames.associateWith { name ->
            if (!profile.has(name)) {
                throw IOException("JM个人资料响应缺少必要字段，已取消改密以保护原资料")
            }
            when (val value = profile.opt(name)) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> throw IOException("JM个人资料字段格式异常，已取消改密以保护原资料")
                else -> value.toString()
            }
        }
    }

    private suspend fun postForm(path: String, fields: List<Pair<String, String>>, secrets: List<String> = emptyList()): String {
        val baseUrl = requireBaseUrl()
        val request = Request.Builder().url("$baseUrl$path").post(formBody(fields)).build()
        return withContext(Dispatchers.IO) {
            val response = execute(request, secrets)
            val root = parseObject(response.body, "JM账号服务响应无法解析", secrets)
            val content = root.optJSONObject("data") ?: root
            val status = content.optString("status").takeIf(String::isNotBlank)
            val message = JmAccountFormPolicy.message(
                status = status,
                msg = content.optString("msg").takeIf(String::isNotBlank),
                errors = content.opt("errors"),
                errorMsg = response.errorMsg ?: optionalMessage(root),
            )
            val safeMessage = JmAccountFormPolicy.redactSecrets(message, secrets)
            if (!response.httpSuccessful) throw IOException(safeMessage.ifBlank { "JM账号请求失败（HTTP ${response.code}）" })
            if (!JmAccountFormPolicy.succeeded(status, response.errorMsg ?: optionalMessage(root))) {
                // In particular, /forgot's ten-minute cooldown is status=fail and must not look like a sent email.
                throw IOException(safeMessage.ifBlank { "JM账号请求未成功" })
            }
            safeMessage.ifBlank { "操作成功" }
        }
    }

    private fun execute(request: Request, secrets: List<String>): HttpResult = try {
        JmNetworkModule.client.newCall(request).execute().use { response ->
            HttpResult(
                body = response.body?.string().orEmpty(),
                httpSuccessful = response.isSuccessful,
                code = response.code,
                errorMsg = response.header(JM_ERROR_MESSAGE_HEADER)?.takeIf(String::isNotBlank),
            )
        }
    } catch (error: IOException) {
        throw IOException(JmAccountFormPolicy.redactSecrets(error.message.orEmpty(), secrets).ifBlank { "JM账号请求失败" }, error)
    }

    private fun parseObject(raw: String, failureMessage: String, secrets: List<String>): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse {
            throw IOException(JmAccountFormPolicy.redactSecrets(failureMessage, secrets))
        }

    private fun extractObject(root: JSONObject, failureMessage: String, secrets: List<String>): JSONObject {
        val data = root.opt("data")
        return when (data) {
            is JSONObject -> data
            is String -> runCatching { JSONObject(data) }.getOrElse {
                throw IOException(JmAccountFormPolicy.redactSecrets(failureMessage, secrets))
            }
            else -> root
        }
    }

    private fun optionalMessage(root: JSONObject): String? =
        if (root.isNull("errorMsg")) null else root.optString("errorMsg").takeIf(String::isNotBlank)

    private fun formBody(fields: List<Pair<String, String>>): FormBody = FormBody.Builder().apply {
        fields.forEach { (name, value) -> add(name, value) }
    }.build()

    private fun requireBaseUrl(): String {
        val baseUrl = JmRouteManager.baseUrl.trimEnd('/')
        if (baseUrl.isBlank()) throw IOException("JM线路尚未就绪")
        return baseUrl
    }

    private data class HttpResult(
        val body: String,
        val httpSuccessful: Boolean,
        val code: Int,
        val errorMsg: String?,
    )

    private const val JM_ERROR_MESSAGE_HEADER = "X-JM-Error-Msg"
}
