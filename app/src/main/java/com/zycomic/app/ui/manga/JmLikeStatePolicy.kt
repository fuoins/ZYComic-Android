package com.zycomic.app.ui.manga

import java.security.MessageDigest

/** A successful JM like is permanent for the authenticated account. */
internal data class JmLikeConfirmation(
    val accountAlbumKey: String,
    val likes: Int?,
)

internal data class JmLikeState(
    val liked: Boolean?,
    val likes: Int?,
)

internal object JmLikeStatePolicy {
    fun accountAlbumKey(accountIdentity: String?, albumId: String): String? =
        accountIdentity?.takeIf(String::isNotBlank)?.let { identity ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$identity|$albumId".toByteArray(Charsets.UTF_8))
            digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

    fun confirmSuccess(accountAlbumKey: String, currentLikes: Int?): JmLikeConfirmation =
        JmLikeConfirmation(accountAlbumKey, currentLikes?.let { if (it < Int.MAX_VALUE) it + 1 else it })

    /** Keep the successful local write above delayed /album values, only for the same account and album. */
    fun mergeAlbum(
        albumLiked: Boolean?,
        albumLikes: Int?,
        currentAccountAlbumKey: String?,
        confirmation: JmLikeConfirmation?,
    ): JmLikeState {
        val confirmedForCurrentAccount = currentAccountAlbumKey != null &&
            confirmation?.accountAlbumKey == currentAccountAlbumKey
        return if (confirmedForCurrentAccount) {
            JmLikeState(liked = true, likes = listOfNotNull(confirmation?.likes, albumLikes).maxOrNull())
        } else {
            JmLikeState(liked = albumLiked, likes = albumLikes)
        }
    }

    fun outcomeUnknownForCurrentSession(currentSessionRevision: Long?, unknownSessionRevision: Long?): Boolean =
        currentSessionRevision != null && currentSessionRevision == unknownSessionRevision
}
