package com.zycomic.app.ui.login

import org.json.JSONArray

/** Pure request/response policy for JM account forms; contains no network or platform dependencies. */
internal object JmAccountFormPolicy {
    val profileFieldNames = listOf(
        "nickName",
        "password",
        "password_confirm",
        "firstName",
        "lastName",
        "gender",
        "birthday",
        "relations",
        "sexuality",
        "website",
        "birthPlace",
        "city",
        "country",
        "occupation",
        "company",
        "school",
        "aboutMe",
        "infoHere",
        "collections",
        "ideal",
        "erogenic",
        "favorite",
        "hate",
        "status",
    )

    fun registrationFields(
        username: String,
        email: String,
        password: String,
        passwordConfirm: String,
    ): List<Pair<String, String>> {
        require(username.isNotBlank()) { "请输入用户名" }
        require(username.trim().length <= 15) { "用户名不能超过15个字符" }
        val normalizedEmail = email.trim()
        require(isSupportedEmail(normalizedEmail)) { "请输入有效的 Gmail 或 QQ 邮箱（不支持 + 别名）" }
        require(password.isNotBlank()) { "请输入密码" }
        require(password == passwordConfirm) { "两次输入的密码不一致" }
        return listOf(
            "username" to username.trim(),
            "email" to normalizedEmail,
            "password" to password,
            "password_confirm" to passwordConfirm,
            "gender" to "Female",
        )
    }

    fun activationFields(username: String, code: String): List<Pair<String, String>> {
        require(username.isNotBlank()) { "请输入用户名" }
        require(code.length == 6 && code.all { it in '0'..'9' }) { "请输入6位数字验证码" }
        return listOf("username" to username.trim(), "numcode" to code)
    }

    fun forgotFields(email: String): List<Pair<String, String>> {
        val normalizedEmail = email.trim()
        require(isSupportedEmail(normalizedEmail)) { "请输入有效的 Gmail 或 QQ 邮箱（不支持 + 别名）" }
        return listOf("email" to normalizedEmail)
    }

    private fun isSupportedEmail(email: String): Boolean =
        '+' !in email && EMAIL_PATTERN.matches(email)

    fun passwordChangeFields(profile: Map<String, String?>, password: String, passwordConfirm: String): List<Pair<String, String>> {
        require(password.isNotBlank()) { "请输入新密码" }
        require(password == passwordConfirm) { "两次输入的密码不一致" }
        val missing = profileFieldNames.filterNot(profile::containsKey)
        require(missing.isEmpty()) { "JM个人资料响应缺少必要字段，已取消改密以保护原资料" }
        return profileFieldNames.map { name ->
            name to when (name) {
                "password" -> password
                "password_confirm" -> passwordConfirm
                else -> profile[name].orEmpty()
            }
        }
    }

    fun nonPasswordFieldsPreserved(before: Map<String, String?>, after: Map<String, String?>): Boolean {
        val fields = profileFieldNames.filterNot { it == "password" || it == "password_confirm" }
        return fields.all { name -> before.containsKey(name) && after.containsKey(name) && before[name] == after[name] }
    }

    /** Parse flexible API `errors` shapes (empty string, message string, or string array). */
    fun message(
        status: String?,
        msg: String?,
        errors: Any?,
        errorMsg: String?,
    ): String {
        val values = when (errors) {
            is String -> listOf(errors)
            is Iterable<*> -> errors.mapNotNull { it?.toString() }
            is Array<*> -> errors.mapNotNull { it?.toString() }
            is JSONArray -> (0 until errors.length()).mapNotNull { index -> errors.opt(index)?.toString() }
            else -> emptyList()
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        val error = errorMsg.orEmpty().trim()
        if (error.isNotEmpty()) return error
        if (status.equals("fail", ignoreCase = true) && values.isNotEmpty()) return values.joinToString("\n")
        return msg.orEmpty().trim().ifEmpty { values.joinToString("\n") }
    }

    fun succeeded(status: String?, errorMsg: String?): Boolean =
        status.equals("ok", ignoreCase = true) || errorMsg == PROFILE_UPDATE_SUCCESS

    fun redactSecrets(message: String, secrets: Collection<String>): String =
        secrets.filter(String::isNotEmpty).distinct().fold(message) { result, secret -> result.replace(secret, "[已隐藏]") }

    const val PROFILE_UPDATE_SUCCESS = "Profile was successfully updated!"

    private val EMAIL_PATTERN = Regex("^[^\\s@+]+@(?:gmail\\.com|qq\\.com)$", RegexOption.IGNORE_CASE)
}
