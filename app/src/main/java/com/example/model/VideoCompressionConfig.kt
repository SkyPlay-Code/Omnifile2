package com.example.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class VideoQualityPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val maxHeight: Int, // 0 = Original
    val targetFps: Float // 0f = Original
) {
    HIGH("high", "High", "Optimized bitrate • Original res & FPS", 0, 0f),
    MEDIUM("medium", "Medium", "Balanced • Max 1080p @ 30 FPS", 1080, 30f),
    LOW("low", "Low", "Compact • Max 720p @ 30 FPS", 720, 30f),
    CUSTOM("custom", "Custom", "Manual advanced overrides", 0, 0f)
}

enum class VideoCodec(val label: String, val description: String) {
    H264("H.264 (AVC)", "Maximum compatibility everywhere"),
    H265("H.265 (HEVC)", "~35% smaller at equal quality")
}

enum class VideoResolution(val label: String, val heightPx: Int) {
    ORIGINAL("Original", 0),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360)
}

data class VideoTargetPreset(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val isDefault: Boolean = false
)

data class VideoCompressionConfig(
    val qualityPreset: VideoQualityPreset = VideoQualityPreset.MEDIUM,
    val targetSizeBytes: Long = 20L * 1024 * 1024,
    val codec: VideoCodec = VideoCodec.H264,
    val resolution: VideoResolution = VideoResolution.P1080,
    val targetFps: Float = 30f, // 0f = Original
    val removeAudio: Boolean = false,
    val audioBitrateKbps: Int = 96,
    val audioVolume: Float = 1.0f // 0.5f to 2.0f
)

object VideoPresetStore {
    private const val PREFS_NAME = "omnifile_video_presets"
    private const val KEY_PRESETS = "target_presets_json"

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    val DEFAULT_PRESETS = listOf(
        VideoTargetPreset("github", "GitHub", 10L * MB, true),
        VideoTargetPreset("discord", "Discord", 20L * MB, true),
        VideoTargetPreset("email", "Email", 25L * MB, true),
        VideoTargetPreset("nitro_basic", "Stories / Nitro Basic", 50L * MB, true),
        VideoTargetPreset("messenger", "Messenger / Bluesky", 100L * MB, true),
        VideoTargetPreset("reels", "Nitro / Reels", 500L * MB, true),
        VideoTargetPreset("twitter", "Twitter / X", 512L * MB, true),
        VideoTargetPreset("whatsapp_tg", "WhatsApp / Telegram", 2L * GB, true),
        VideoTargetPreset("tg_prem", "TG Premium / Feed", 4L * GB, true),
        VideoTargetPreset("x_prem", "X Premium", 8L * GB, true)
    )

    fun loadPresets(context: Context): List<VideoTargetPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRESETS, null) ?: return DEFAULT_PRESETS
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VideoTargetPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VideoTargetPreset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        sizeBytes = obj.getLong("sizeBytes"),
                        isDefault = obj.optBoolean("isDefault", false)
                    )
                )
            }
            if (list.isEmpty()) DEFAULT_PRESETS else list
        } catch (_: Exception) {
            DEFAULT_PRESETS
        }
    }

    fun savePresets(context: Context, presets: List<VideoTargetPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("sizeBytes", p.sizeBytes)
                    put("isDefault", p.isDefault)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .apply()
    }

    fun resetDefaults(context: Context): List<VideoTargetPreset> {
        savePresets(context, DEFAULT_PRESETS)
        return DEFAULT_PRESETS
    }
}