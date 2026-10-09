package com.instadownloader.app

import org.junit.Assert.*
import org.junit.Test

class InstagramParserTest {

    // ---------- URL / shortcode ----------

    @Test
    fun validPhotoUrlExtractsShortcode() {
        assertEquals("ABC123xyz", InstagramParser.extractShortcode("https://www.instagram.com/p/ABC123xyz/"))
    }

    @Test
    fun validReelUrlExtractsShortcode() {
        assertEquals("ReEl_99", InstagramParser.extractShortcode("https://www.instagram.com/reel/ReEl_99/"))
    }

    @Test
    fun validTvUrlExtractsShortcode() {
        assertEquals("TV123", InstagramParser.extractShortcode("https://instagram.com/tv/TV123"))
    }

    @Test
    fun invalidUrlReturnsNull() {
        assertNull(InstagramParser.extractShortcode("https://example.com/not-instagram"))
        assertNull(InstagramParser.extractShortcode(""))
        assertNull(InstagramParser.extractShortcode("https://www.instagram.com/username/"))
    }

    @Test
    fun isValidPostUrl() {
        assertTrue(InstagramParser.isValidPostUrl("https://www.instagram.com/p/ABC123/"))
        assertFalse(InstagramParser.isValidPostUrl("https://google.com"))
    }

    // ---------- data-sjs extraction ----------

    @Test
    fun extractsValidSjsJson() {
        val html = """
            <html>
            <script type="application/json" data-sjs>{"pk":"123","ok":true}</script>
            </html>
        """.trimIndent()
        val list = InstagramParser.extractSjsJsonObjects(html)
        assertEquals(1, list.size)
        assertEquals("123", list[0].optString("pk"))
        assertTrue(list[0].optBoolean("ok"))
    }

    @Test
    fun extractsNestedJsonAndArrays() {
        val html = """
            <script data-sjs>
            {
              "id": "456",
              "carousel_media": [
                {"image_versions2": {"candidates": [{"url": "https://cdn.example/a.jpg", "width": 1080}]}},
                {"video_versions": [{"url": "https://cdn.example/b.mp4", "width": 720}]}
              ]
            }
            </script>
        """.trimIndent()
        val list = InstagramParser.extractSjsJsonObjects(html)
        assertEquals(1, list.size)
        val carousel = list[0].optJSONArray("carousel_media")
        assertNotNull(carousel)
        assertEquals(2, carousel!!.length())
        assertTrue(carousel.getJSONObject(0).has("image_versions2"))
        assertTrue(carousel.getJSONObject(1).has("video_versions"))
    }

    @Test
    fun extractsMultilineScriptContent() {
        val html = """
            <script data-sjs>
            {
              "pk": "999",
              "image_versions2": {
                "candidates": [
                  {
                    "url": "https://cdn.example/photo.jpg",
                    "width": 1080,
                    "height": 1350
                  }
                ]
              }
            }
            </script>
        """.trimIndent()
        val list = InstagramParser.extractSjsJsonObjects(html)
        assertEquals(1, list.size)
        assertEquals("999", list[0].optString("pk"))
    }

    @Test
    fun missingDataSjsReturnsEmpty() {
        val html = "<html><body>No scripts here</body></html>"
        assertTrue(InstagramParser.extractSjsJsonObjects(html).isEmpty())
    }

    @Test
    fun malformedJsonIsSkipped() {
        val html = """
            <script data-sjs>{ this is not json }</script>
            <script data-sjs>{"pk":"valid"}</script>
        """.trimIndent()
        val list = InstagramParser.extractSjsJsonObjects(html)
        assertEquals(1, list.size)
        assertEquals("valid", list[0].optString("pk"))
    }

    // ---------- login wall / unavailable ----------

    @Test
    fun detectsLoginWall() {
        assertTrue(InstagramParser.isLoginWall("<html>login_required</html>"))
        assertTrue(InstagramParser.isLoginWall("<form><input name=\"password\"></form>"))
        assertFalse(InstagramParser.isLoginWall("<html>normal post page with lots of content</html>" + "x".repeat(60_000)))
    }

    @Test
    fun parsePostHtmlThrowsOnLoginWall() {
        try {
            InstagramParser.parsePostHtml("<html>login_required</html>", "ABC")
            fail("Expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("login", ignoreCase = true))
        }
    }

    @Test
    fun parsePostHtmlThrowsOnMissingScripts() {
        try {
            InstagramParser.parsePostHtml("<html><body>empty</body></html>", "ABC")
            fail("Expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("No usable media data", ignoreCase = true))
        }
    }

    // ---------- photo / reel / carousel ----------

    @Test
    fun parsesPhotoPost() {
        val shortcode = "PHOTO1"
        val mediaId = InstagramParser.shortcodeToMediaId(shortcode)
        val html = """
            <script data-sjs>
            {
              "pk": "$mediaId",
              "id": "$mediaId",
              "image_versions2": {
                "candidates": [
                  {"url": "https://cdn.example/photo.jpg", "width": 1080, "height": 1350}
                ]
              },
              "user": {"username": "testuser"},
              "caption": {"text": "A photo"}
            }
            </script>
        """.trimIndent()
        val items = InstagramParser.parsePostHtml(html, shortcode)
        assertEquals(1, items.size)
        assertFalse(items[0].isVideo)
        assertEquals("https://cdn.example/photo.jpg", items[0].url)
        assertEquals(1080, items[0].width)
        assertEquals("testuser", items[0].meta?.username)
    }

    @Test
    fun parsesReelVideoPost() {
        val shortcode = "REEL1"
        val mediaId = InstagramParser.shortcodeToMediaId(shortcode)
        val html = """
            <script data-sjs>
            {
              "pk": "$mediaId",
              "video_versions": [
                {"url": "https://cdn.example/reel.mp4", "width": 1080, "height": 1920}
              ],
              "video_duration": 15.5,
              "user": {"username": "reeler"}
            }
            </script>
        """.trimIndent()
        val items = InstagramParser.parsePostHtml(html, shortcode)
        assertEquals(1, items.size)
        assertTrue(items[0].isVideo)
        assertEquals("https://cdn.example/reel.mp4", items[0].url)
        assertEquals(15.5, items[0].durationSec, 0.01)
    }

    @Test
    fun parsesCarouselInOrder() {
        val shortcode = "CAROUSEL1"
        val mediaId = InstagramParser.shortcodeToMediaId(shortcode)
        val html = """
            <script data-sjs>
            {
              "pk": "$mediaId",
              "carousel_media": [
                {"image_versions2": {"candidates": [{"url": "https://cdn.example/1.jpg", "width": 1080}]}},
                {"image_versions2": {"candidates": [{"url": "https://cdn.example/2.jpg", "width": 1080}]}},
                {"video_versions": [{"url": "https://cdn.example/3.mp4", "width": 720}]}
              ],
              "user": {"username": "carouseluser"}
            }
            </script>
        """.trimIndent()
        val items = InstagramParser.parsePostHtml(html, shortcode)
        assertEquals(3, items.size)
        assertEquals("https://cdn.example/1.jpg", items[0].url)
        assertEquals("https://cdn.example/2.jpg", items[1].url)
        assertEquals("https://cdn.example/3.mp4", items[2].url)
        assertFalse(items[0].isVideo)
        assertFalse(items[1].isVideo)
        assertTrue(items[2].isVideo)
        assertTrue(items[0].baseName.endsWith("_0"))
        assertTrue(items[1].baseName.endsWith("_1"))
        assertTrue(items[2].baseName.endsWith("_2"))
    }

    @Test
    fun prefersHighestWidthCandidate() {
        val shortcode = "BEST"
        val mediaId = InstagramParser.shortcodeToMediaId(shortcode)
        val html = """
            <script data-sjs>
            {
              "pk": "$mediaId",
              "image_versions2": {
                "candidates": [
                  {"url": "https://cdn.example/small.jpg", "width": 320},
                  {"url": "https://cdn.example/large.jpg", "width": 1080},
                  {"url": "https://cdn.example/med.jpg", "width": 640}
                ]
              }
            }
            </script>
        """.trimIndent()
        val items = InstagramParser.parsePostHtml(html, shortcode)
        assertEquals("https://cdn.example/large.jpg", items[0].url)
        assertEquals(1080, items[0].width)
    }
}
