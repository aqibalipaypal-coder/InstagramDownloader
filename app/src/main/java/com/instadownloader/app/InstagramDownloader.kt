package com.instadownloader.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class PostMeta(
    val username: String?,
    val caption: String?,
    val url: String,
    val song: String?,
    val takenAtSec: Long,
)

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val reduced: Boolean = false,
    val baseName: String = "",
    val meta: PostMeta? = null,
) {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
}

object InstagramDownloader {

    private val PROFILE_REGEX = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/([A-Za-z0-9_.]+)/?(?:[?#].*)?$"
    )
    private val RESERVED_PROFILE_PATHS = setOf(
        "p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct",
        "about", "developer", "legal", "privacy", "graphql", "web", "download", "emails", "topics"
    )

    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                removeAll { c -> cookies.any { it.name == c.name } }
                addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private val GOOGLEBOT_UA = "Googlebot/2.1 (+http://www.google.com/bot.html)"

    fun getMediaItems(postUrl: String, targetWidth: Int = Int.MAX_VALUE): List<MediaResult> {
        val trimmed = postUrl.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("Empty URL")

        val shortcode = InstagramParser.extractShortcode(trimmed) ?: run {
            extractProfileUsername(trimmed)?.let { username ->
                return listOf(fetchProfilePicture(username))
            }
            throw IllegalArgumentException("Unsupported or invalid Instagram URL. Use a public post, Reel, or TV link.")
        }
        return tryPostPage(shortcode)
    }

    private fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url)
        return if (m.matches()) m.group(1)?.takeUnless { it.lowercase() in RESERVED_PROFILE_PATHS } else null
    }

    fun isProfileUrl(url: String): Boolean = extractProfileUsername(url) != null

    private fun tryPostPage(shortcode: String): List<MediaResult> {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/p/$shortcode/")
                .header("User-Agent", GOOGLEBOT_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get().build()
        ).execute()

        val html = response.body?.string()
            ?: throw Exception("Empty response from Instagram (HTTP ${response.code})")

        if (!response.isSuccessful) {
            throw Exception("Instagram returned HTTP ${response.code}. The post may be private, deleted, or rate-limited.")
        }

        // All parsing is delegated to the pure, tested InstagramParser.
        return InstagramParser.parsePostHtml(html, shortcode)
    }

    private fun fetchProfilePicture(username: String): MediaResult {
        val url = "https://www.instagram.com/$username/"
        val response = client.newCall(
            Request.Builder().url(url).header("User-Agent", MOBILE_UA).get().build()
        ).execute()
        val html = response.body?.string() ?: throw Exception("Profile fetch failed")
        val picMatch = Regex("""profile_pic_url_hd":"([^"]+)""").find(html)
        val picUrl = picMatch?.groupValues?.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?: throw Exception("No profile picture found for @$username")
        return MediaResult(url = picUrl, isVideo = false, baseName = username, meta = null)
    }
}
