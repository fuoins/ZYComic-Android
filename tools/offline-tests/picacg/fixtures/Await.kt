package eu.kanade.tachiyomi.network
import okhttp3.Call
import okhttp3.Response
suspend fun Call.awaitSuccess():Response=execute()
