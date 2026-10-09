package com.instadownloader.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Pure, network-free Instagram HTML/JSON parser.
 * All functions here can be unit-tested without OkHttp or a device.
 */
object InstagramParser {

    private val SHORTCODE_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)"
    )

    // Simple, robust regex: locate script tags that contain data-sjs and capture their full content.
    // Does not attempt to match JSON braces. DOT_MATCHES_ALL allows multiline.
    private val DATA_SJS_SCRIPT_REGEX = Regex(
        """<script[^>]*data-sjs[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url.trim())
        return if (m.find()) m.group(1) else null
    }

    fun isValidPostUrl(url: String): Boolean = extractShortcode(url) != null

    fun isLoginWall(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("login_required") ||
               lower.contains("checkpoint_required") ||
               (lower.contains("name=\"password\"") && lower.contains("login") && lower.length < 30_000)
    }

    /**
     * Extracts every data-sjs script body and attempts to parse it as JSON.
     * Returns only successfully parsed JSON objects.
     */
    fun extractSjsJsonObjects(html: String): List<JSONObject> {
        return DATA_SJS_SCRIPT_REGEX.findAll(html)
            .mapNotNull { match ->
                val raw = match.groupValues.getOrNull(1)?.trim() ?: return@mapNotNull null
                if (raw.isEmpty()) return@mapNotNull null
                runCatching { JSONObject(raw) }.getOrNull()
            }
            .toList()
    }

    fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (character in shortcode) {
            val digit = alphabet.indexOf(character)
            if (digit < 0) throw IllegalArgumentException("Invalid Instagram shortcode")
            id = Math.addExact(Math.multiplyExact(id, 64L), digit.toLong())
        }
        return id.toString()
    }

    fun findPublicProduct(value: Any?, expectedMediaId: String): JSONObject? {
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

    fun extractProductMedia(product: JSONObject, baseName: String, meta: PostMeta): List<MediaResult> {
        val results = mutableListOf<MediaResult>()
        val carousel = product.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val child = carousel.optJSONObject(i) ?: continue
                results.addAll(extractSingleMedia(child, "${baseName}_$i", meta))
            }
        } else {
            results.addAll(extractSingleMedia(product, baseName, meta))
        }
        return results
    }

    private fun extractSingleMedia(media: JSONObject, baseName: String, meta: PostMeta): List<MediaResult> {
        val results = mutableListOf<MediaResult>()

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

    /**
     * Full parse of a saved HTML page for a known shortcode.
     * Returns media items or throws with a clear message.
     */
    fun parsePostHtml(html: String, shortcode: String): List<MediaResult> {
        if (isLoginWall(html)) {
            throw Exception("This post requires login or is not publicly accessible. Only public posts are supported.")
        }
        val expectedMediaId = shortcodeToMediaId(shortcode)
        val candidates = extractSjsJsonObjects(html)
        if (candidates.isEmpty()) {
            throw Exception("No usable media data found. Instagram may have changed its page structure or blocked the request.")
        }
        for (json in candidates) {
            val product = findPublicProduct(json, expectedMediaId) ?: continue
            val meta = PostMeta(
                username = product.optJSONObject("user")?.optString("username"),
                caption = product.optJSONObject("caption")?.optString("text"),
                url = "https://www.instagram.com/p/$shortcode/",
                song = product.optJSONObject("music_metadata")?.optString("music_canonical_id"),
                takenAtSec = product.optLong("taken_at", 0)
            )
            val baseName = (product.optJSONObject("user")?.optString("username") ?: "ig") + "_$shortcode"
            val media = extractProductMedia(product, baseName, meta)
            if (media.isNotEmpty()) return media
        }
        throw Exception("Public media could not be extracted from this post. It may be restricted or the format is unsupported.")
    }
}
