package app.cracker.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
const val DASH_XML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

fun buildHttpClient(cookieHeader: () -> String?): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .apply {
                    if (original.header("Accept") == null) header("Accept", "*/*")
                }
                .header("Origin", "https://chzzk.naver.com")
                .header("Referer", "https://chzzk.naver.com/")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(CookieInterceptor(cookieHeader))
        .build()

class CookieInterceptor(private val cookieHeader: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val cookie = cookieHeader()
        val request = if (!cookie.isNullOrBlank() && isNaverHost(host)) {
            chain.request().newBuilder().header("Cookie", cookie).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

fun isNaverHost(host: String): Boolean {
    val value = host.lowercase()
    return value == "naver.com" || value.endsWith(".naver.com")
}

fun isChzzkHost(host: String): Boolean {
    val value = host.lowercase()
    return value == "chzzk.naver.com" || value.endsWith(".chzzk.naver.com")
}

fun OkHttpClient.getText(url: String, accept: String? = null): String {
    val request = Request.Builder().url(url).get().apply {
        if (accept != null) header("Accept", accept)
    }.build()
    val response = newCall(request).execute()
    response.use {
        val body = it.body.string()
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        return body
    }
}

fun OkHttpClient.download(
    url: String,
    sink: java.io.OutputStream,
    onBytes: ((copied: Long, total: Long) -> Unit)? = null,
) {
    val response = newCall(Request.Builder().url(url).get().build()).execute()
    response.use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        val body = it.body
        val total = body.contentLength()
        val input = body.byteStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var lastCallbackNs = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            copied += read
            if (onBytes != null) {
                val now = System.nanoTime()
                if (now - lastCallbackNs >= 150_000_000L || (total > 0 && copied >= total)) {
                    lastCallbackNs = now
                    onBytes(copied, total)
                }
            }
        }
        sink.flush()
    }
}
