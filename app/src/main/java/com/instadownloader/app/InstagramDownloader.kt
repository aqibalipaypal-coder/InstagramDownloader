package com.instadownloader.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

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

    private val SHORTCODE_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)"
    )
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
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun getMediaItems(postUrl: String, targetWidth: Int = Int.MAX_VALUE): List<MediaResult> {
        val shortcode = extractShortcode(postUrl) ?: run {
            extractProfileUsername(postUrl)?.let { username ->
                return listOf(fetchProfilePicture(username))
            }
            throw IllegalArgumentException("Invalid Instagram URL: $postUrl")
        }
        return tryPostPage(shortcode, targetWidth)
    }

    private fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url.trim())
        return if (m.find()) m.group(1) else null
    }

    private fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url.trim())
        return if (m.matches()) m.group(1)?.takeUnless { it.lowercase() in RESERVED_PROFILE_PATHS } else null
    }

    fun isProfileUrl(url: String): Boolean = extractProfileUsername(url) != null

    private fun tryPostPage(shortcode: String, targetWidth: Int): List<MediaResult> {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/p/$shortcode/")
                .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                .get().build()
        ).execute()

        val html = response.body?.string()
            ?: throw Exception("Post HTTP ${response.code}: empty body")
        if (!response.isSuccessful) throw Exception("Post HTTP ${response.code}")

        val expectedMediaId = shortcodeToMediaId(shortcode)
        Regex("""<script\\b[^>]*\\bdata-sjs[^>]*>(\\{.+?\\})</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .mapNotNull { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
            .mapNotNull { findPublicProduct(it, expectedMediaId) }
            .map { extractProductMedia(it, targetWidth, postBaseName(it, shortcode), postMeta(it, shortcode)) }
            .firstOrNull { it.isNotEmpty() }
            ?.let { return it }
        throw Exception("Post HTTP ${response.code}: no public media found")
    }

    private fun findPublicProduct(value: Any?, expectedMediaId: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("if_not_gated_logged_out")?.let {
                    if (it.optString("pk") == expectedMediaId || it.optString("id") == expectedMediaId)
                        return it
                }
                if ((value.optString("pk") == expectedMediaId || value.optString("id") == expectedMediaId) &&
                    (value.has("video_versions") || value.has("carousel_media") || value.has("image_versions2")))
                    return value

                val keys = value.keys()
                while (keys.hasNext()) {
                    findPublicProduct(value.opt(keys.next()), expectedMediaId)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findPublicProduct(value.opt(i), expectedMediaId)?.let { return it }
            }
        }
        return null
    }

    private fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (character in shortcode) {
            val digit = alphabet.indexOf(character)
            require(digit >= 0) { "Invalid Instagram shortcode" }
            id = Math.addExact(Math.multiplyExact(id, 64L), digit.toLong())
        }
        return id.toString()
    }

    private fun postBaseName(product: JSONObject, shortcode: String): String {
        val user = product.optJSONObject("user")?.optString("username") ?: "unknown"
        return "${user}_${shortcode}"
    }

    private fun postMeta(product: JSONObject, shortcode: String): PostMeta {
        val user = product.optJSONObject("user")
        return PostMeta(
            username = user?.optString("username"),
            caption = product.optJSONObject("caption")?.optString("text"),
            url = "https://www.instagram.com/p/$shortcode/",
            song = product.optJSONObject("music_metadata")?.optString("music_canonical_id"),
            takenAtSec = product.optLong("taken_at", 0)
        )
    }

    private fun extractProductMedia(product: JSONObject, targetWidth: Int, baseName: String, meta: PostMeta): List<MediaResult> {
        val results = mutableListOf<MediaResult>()
        val carousel = product.optJSONArray("carousel_media")
        if (carousel != null) {
            for (i in 0 until carousel.length()) {
                val child = carousel.getJSONObject(i)
                results.addAll(extractSingleMedia(child, targetWidth, "${baseName}_$i", meta))
            }
        } else {
            results.addAll(extractSingleMedia(product, targetWidth, baseName, meta))
        }
        return results
    }

    private fun extractSingleMedia(media: JSONObject, targetWidth: Int, baseName: String, meta: PostMeta): List<MediaResult> {
        val results = mutableListOf<MediaResult>()
        val videoVersions = media.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            val best = (0 until videoVersions.length()).map { videoVersions.getJSONObject(it) }
                .maxByOrNull { it.optInt("width", 0) }
            best?.let {
                results.add(MediaResult(
                    url = it.getString("url"),
                    isVideo = true,
                    width = it.optInt("width"),
                    height = it.optInt("height"),
                    durationSec = media.optDouble("video_duration", 0.0),
                    baseName = baseName,
                    meta = meta
                ))
            }
        } else {
            val images = media.optJSONObject("image_versions2")?.optJSONArray("candidates")
            if (images != null && images.length() > 0) {
                val best = (0 until images.length()).map { images.getJSONObject(it) }
                    .maxByOrNull { it.optInt("width", 0) }
                best?.let {
                    results.add(MediaResult(
                        url = it.getString("url"),
                        isVideo = false,
                        width = it.optInt("width"),
                        height = it.optInt("height"),
                        baseName = baseName,
                        meta = meta
                    ))
                }
            }
        }
        return results
    }

    private fun fetchProfilePicture(username: String): MediaResult {
        // Simplified profile pic fetch; may not always succeed
        val url = "https://www.instagram.com/$username/"
        val response = client.newCall(
            Request.Builder().url(url).header("User-Agent", MOBILE_UA).get().build()
        ).execute()
        val html = response.body?.string() ?: throw Exception("Profile fetch failed")
        // Very basic extraction; real implementation would parse better
        val picMatch = Regex("""profile_pic_url_hd":"([^"]+)""").find(html)
        val picUrl = picMatch?.groupValues?.get(1)?.replace("\\u0026", "&") ?: throw Exception("No profile pic found")
        return MediaResult(url = picUrl, isVideo = false, baseName = username, meta = null)
    }
}
