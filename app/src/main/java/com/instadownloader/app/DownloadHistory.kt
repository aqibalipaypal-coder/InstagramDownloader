package com.instadownloader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DownloadRecord(
    val filename: String,
    val type: String, // photo, video, zip
    val date: Long,
    val size: Long,
    val uri: String?
)

object DownloadHistory {
    private const val PREFS = "download_history"
    private const val KEY = "items"

    fun load(context: Context): List<DownloadRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadRecord(
                    filename = o.getString("filename"),
                    type = o.getString("type"),
                    date = o.getLong("date"),
                    size = o.optLong("size", 0),
                    uri = o.optString("uri", null)
                )
            }.sortedByDescending { it.date }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, record: DownloadRecord) {
        val current = load(context).toMutableList()
        current.add(0, record)
        // Keep last 100
        val trimmed = current.take(100)
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(JSONObject().apply {
                put("filename", r.filename)
                put("type", r.type)
                put("date", r.date)
                put("size", r.size)
                put("uri", r.uri ?: "")
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun formatDate(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
    }
}
