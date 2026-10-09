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

    // Safe regex: only locates the script tag and captures its full inner text.
    // Does NOT try to match JSON braces. DOT_MATCHES_ALL allows multiline content.
    private val DATA_SJS_SCRIPT_REGEX = Regex(
        """<script\\b[^>]*\\bdata-sjs\\b[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
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

        val shortcode = extractShortcode(trimmed) ?: run {
            extractProfileUsername(trimmed)?.let { username ->
                return listOf(fetchProfilePicture(username))
            }
            throw IllegalArgumentException("Unsupported or invalid Instagram URL. Use a public post, Reel, or TV link.")
        }
        return tryPostPage(shortcode, targetWidth)
    }

    private fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url)
        return if (m.matches()) m.group(1)?.takeUnless { it.lowercase() in RESERVED_PROFILE_PATHS } else null
    }

    fun isProfileUrl(url: String): Boolean = extractProfileUsername(url) != null

    private fun tryPostPage(shortcode: String, targetWidth: Int): List<MediaResult> {
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

        if (isLoginWall(html)) {
            throw Exception("This post requires login or is not publicly accessible. Only public posts are supported.")
        }

        val expectedMediaId = shortcodeToMediaId(shortcode)

        // Extract every data-sjs script body, then try to parse as JSON.
        val candidates = DATA_SJS_SCRIPT_REGEX.findAll(html)
            .mapNotNull { match ->
                val raw = match.groupValues.getOrNull(1)?.trim() ?: return@mapNotNull null
                if (raw.isEmpty()) return@mapNotNull null
                runCatching { JSONObject(raw) }.getOrNull()
            }
            .toList()

        if (candidates.isEmpty()) {
            throw Exception("No usable media data found. Instagram may have changed its page structure or blocked the request.")
        }

        for (json in candidates) {
            val product = findPublicProduct(json, expectedMediaId) ?: continue
            val media = extractProductMedia(
                product,
                targetWidth,
                postBaseName(product, shortcode),
                postMeta(product, shortcode)
            )
            if (media.isNotEmpty()) return media
        }

        throw Exception("Public media could not be extracted from this post. It may be restricted or the format is unsupported.")
    }

    private fun isLoginWall(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("login_required") ||
               lower.contains("checkpoint") ||
               (lower.contains("login") && lower.contains("password") && lower.length < 50_000)
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
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findPublicProduct(value.opt(i), expectedMediaId)?.let { return it }
                }
            }
        }
        return null
    }

    private fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (character in shortcode) {
            val digit = alphabet.indexOf(character)
            if (digit < 0) throw IllegalArgumentException("Invalid Instagram shortcode")
            id = Math.addExact(Math.multiplyExact(id, 64L), digit.toLong())
        }
        return id.toString()
    }

    private fun postBaseName(product: JSONObject, shortcode: String): String {
        val user = product.optJSONObject("user")?.optString("username") ?: "ig"
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
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val child = carousel.optJSONObject(i) ?: continue
                results.addAll(extractSingleMedia(child, targetWidth, "${baseName}_$i", meta))
            }
        } else {
            results.addAll(extractSingleMedia(product, targetWidth, baseName, meta))
        }
        return results
    }

    private fun extractSingleMedia(media: JSONObject, targetWidth: Int, baseName: String, meta: PostMeta): List<MediaResult> {
        val results = mutableListOf<MediaResult>()

        // Prefer actual video files
        val videoVersions = media.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            val best = (0 until videoVersions.length())
                .mapNotNull { videoVersions.optJSONObject(it) }
                .maxByOrNull { it.optInt("width", 0) }
            best?.optString("url")?.takeIf { it.startsWith("http") }?.let { url ->
                results.add(
                    MediaResult(
                        url = url,
                        isVideo = true,
                        width = best.optInt("width"),
                        height = best.optInt("height"),
                        durationSec = media.optDouble("video_duration", 0.0),
                        baseName = baseName,
                        meta = meta
                    )
                )
                return results
            }
        }

        // Images
        val images = media.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (images != null && images.length() > 0) {
            val best = (0 until images.length())
                .mapNotNull { images.optJSONObject(it) }
                .maxByOrNull { it.optInt("width", 0) }
            best?.optString("url")?.takeIf { it.startsWith("http") }?.let { url ->
                results.add(
                    MediaResult(
                        url = url,
                        isVideo = false,
                        width = best.optInt("width"),
                        height = best.optInt("height"),
                        baseName = baseName,
                        meta = meta
                    )
                )
            }
        }
        return results
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
