package com.example.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VideoQualityPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val defaultResolution: VideoResolution,
    val defaultFps: Float?
) {
    HIGH(
        id = "high",
        label = "High",
        subtitle = "Optimized bitrate • Original res & FPS",
        defaultResolution = VideoResolution.ORIGINAL,
        defaultFps = null
    ),
    MEDIUM(
        id = "medium",
        label = "Medium",
        subtitle = "Balanced • Max 1080p @ 30 FPS",
        defaultResolution = VideoResolution.P1080,
        defaultFps = 30f
    ),
    LOW(
        id = "low",
        label = "Low",
        subtitle = "Compact • Max 720p @ 30 FPS",
        defaultResolution = VideoResolution.P720,
        defaultFps = 30f
    ),
    CUSTOM(
        id = "custom",
        label = "Custom",
        subtitle = "Manual advanced overrides",
        defaultResolution = VideoResolution.ORIGINAL,
        defaultFps = null
    );

    companion object {
        fun fromId(id: String?): VideoQualityPreset? {
            val normalized = id?.trim() ?: return null
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }

        fun matching(resolution: VideoResolution, targetFps: Float?): VideoQualityPreset =
            entries.firstOrNull {
                it != CUSTOM && it.defaultResolution == resolution && it.defaultFps == targetFps
            } ?: CUSTOM
    }
}

enum class VideoContainer(
    val id: String,
    val extension: String,
    val mimeType: String
) {
    MP4(id = "mp4", extension = "mp4", mimeType = "video/mp4"),
    WEBM(id = "webm", extension = "webm", mimeType = "video/webm");

    fun supportedVideoCodecs(apiLevel: Int = Int.MAX_VALUE): Set<VideoCodec> = when (this) {
        MP4 -> buildSet {
            add(VideoCodec.H264)
            if (apiLevel >= VideoCodec.H265.minApiLevel) add(VideoCodec.H265)
            if (apiLevel >= VideoCodec.AV1.minApiLevel) add(VideoCodec.AV1)
        }
        WEBM -> buildSet {
            if (apiLevel >= VideoCodec.VP9.minApiLevel) add(VideoCodec.VP9)
            if (apiLevel >= VideoCodec.AV1.minApiLevel) add(VideoCodec.AV1)
        }
    }

    // Android framework MediaMuxer supports AAC/AMR in MP4 (not Opus), and Opus in WebM
    fun supportedAudioCodecs(apiLevel: Int = Int.MAX_VALUE): Set<VideoAudioCodec> = when (this) {
        MP4 -> buildSet {
            add(VideoAudioCodec.AAC)
            add(VideoAudioCodec.COPY)
            add(VideoAudioCodec.MUTE)
        }
        WEBM -> buildSet {
            if (apiLevel >= VideoAudioCodec.OPUS.minApiLevel) add(VideoAudioCodec.OPUS)
            add(VideoAudioCodec.MUTE)
        }
    }

    fun defaultAudioCodec(apiLevel: Int = Int.MAX_VALUE): VideoAudioCodec =
        supportedAudioCodecs(apiLevel).firstOrNull { it.requiresReencode } ?: VideoAudioCodec.MUTE

    companion object {
        fun fromId(id: String?): VideoContainer? {
            val normalized = id?.trim() ?: return null
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }
    }
}

enum class VideoCodec(
    val id: String,
    val label: String,
    val description: String,
    val mimeType: String,
    val minApiLevel: Int,
    val qualityEfficiencyFactor: Float
) {
    H264("h264", "H.264 (AVC)", "Maximum compatibility everywhere", "video/avc", 21, 1.0f),
    H265("h265", "H.265 (HEVC)", "~35% smaller at equal quality", "video/hevc", 21, 1.35f),
    VP9("vp9", "VP9", "Open royalty-free web codec", "video/x-vnd.on2.vp9", 21, 1.30f),
    AV1("av1", "AV1", "Next-gen ultra-efficient codec (Android 14+)", "video/av01", 34, 1.50f);

    fun preferredContainer(): VideoContainer = when (this) {
        VP9 -> VideoContainer.WEBM
        H264, H265, AV1 -> VideoContainer.MP4
    }

    fun isAllowed(enableAllCodecs: Boolean, apiLevel: Int = Int.MAX_VALUE): Boolean =
        apiLevel >= minApiLevel && (enableAllCodecs || this == H264 || this == H265)

    companion object {
        fun fromId(id: String?): VideoCodec? {
            val normalized = id?.trim() ?: return null
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }
    }
}

enum class VideoAudioCodec(
    val id: String,
    val label: String,
    val description: String,
    val requiresReencode: Boolean,
    val minApiLevel: Int
) {
    AAC("AAC", "AAC", "Universal compatibility (MP4)", true, 21),
    OPUS("OPUS", "Opus", "High efficiency (WebM / Android 10+)", true, 29),
    COPY("COPY", "Copy", "Passthrough original audio (MP4)", false, 21),
    MUTE("MUTE", "Mute", "Strip audio track completely", false, 21);

    companion object {
        fun fromId(id: String?): VideoAudioCodec? {
            val normalized = id?.trim() ?: return null
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }
    }
}

enum class VideoResolution(
    val id: String,
    val label: String,
    val maxShortSidePx: Int?
) {
    ORIGINAL("original", "Original", null),
    P1080("1080p", "1080p", 1080),
    P720("720p", "720p", 720),
    P480("480p", "480p", 480),
    P360("360p", "360p", 360);

    companion object {
        fun fromId(id: String?): VideoResolution? {
            val normalized = id?.trim() ?: return null
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }
    }
}

data class VideoTargetPreset(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val isDefault: Boolean = false
) {
    init {
        require(id.isNotBlank() && id == id.trim()) { "Preset id must be non-blank and trimmed" }
        require(name.isNotBlank() && name == name.trim()) { "Preset name must be non-blank and trimmed" }
        require(sizeBytes in VideoCompressionConfig.MIN_TARGET_SIZE_BYTES..VideoCompressionConfig.MAX_TARGET_SIZE_BYTES) {
            "Preset sizeBytes out of bounds (was $sizeBytes)"
        }
    }
}

data class VideoCompressionConfig(
    val qualityPreset: VideoQualityPreset = VideoQualityPreset.MEDIUM,
    val targetSizeBytes: Long = DEFAULT_TARGET_SIZE_BYTES,
    val codec: VideoCodec = VideoCodec.H264,
    val container: VideoContainer = codec.preferredContainer(),
    val resolution: VideoResolution = qualityPreset.defaultResolution,
    val targetFps: Float? = qualityPreset.defaultFps,
    val audioCodec: VideoAudioCodec = container.defaultAudioCodec(),
    val audioBitrateKbps: Int = DEFAULT_AUDIO_BITRATE_KBPS,
    val audioVolume: Float = DEFAULT_AUDIO_VOLUME,
    val enableAllCodecs: Boolean = false
) {
    init {
        require(targetSizeBytes in MIN_TARGET_SIZE_BYTES..MAX_TARGET_SIZE_BYTES) {
            "targetSizeBytes out of bounds (was $targetSizeBytes)"
        }
        require(targetFps == null || (!targetFps.isNaN() && !targetFps.isInfinite() && targetFps in MIN_FPS..MAX_FPS)) {
            "targetFps must be null or finite in $MIN_FPS..$MAX_FPS (was $targetFps)"
        }
        require(audioBitrateKbps in MIN_AUDIO_BITRATE_KBPS..MAX_AUDIO_BITRATE_KBPS) {
            "audioBitrateKbps out of bounds (was $audioBitrateKbps)"
        }
        require(!audioVolume.isNaN() && !audioVolume.isInfinite() && audioVolume in MIN_AUDIO_VOLUME..MAX_AUDIO_VOLUME) {
            "audioVolume must be finite in $MIN_AUDIO_VOLUME..$MAX_AUDIO_VOLUME (was $audioVolume)"
        }
        require(
            qualityPreset == VideoQualityPreset.CUSTOM ||
                (resolution == qualityPreset.defaultResolution && targetFps == qualityPreset.defaultFps)
        ) {
            "qualityPreset ($qualityPreset) conflicts with resolution=$resolution / targetFps=$targetFps"
        }
        require(enableAllCodecs || codec == VideoCodec.H264 || codec == VideoCodec.H265) {
            "Codec ${codec.id} requires enableAllCodecs = true"
        }
        require(codec in container.supportedVideoCodecs()) {
            "Video codec ${codec.id} is incompatible with container ${container.id}"
        }
        require(audioCodec in container.supportedAudioCodecs()) {
            "Audio codec ${audioCodec.id} is incompatible with container ${container.id}"
        }
    }

    val isAudioMuted: Boolean
        get() = audioCodec == VideoAudioCodec.MUTE

    val removeAudio: Boolean
        get() = isAudioMuted

    val effectiveAudioMode: String
        get() = audioCodec.id

    val effectiveAudioBitrateKbps: Int?
        get() = if (audioCodec.requiresReencode) audioBitrateKbps else null

    val effectiveAudioVolume: Float?
        get() = if (audioCodec.requiresReencode) audioVolume else null

    fun withQualityPreset(preset: VideoQualityPreset): VideoCompressionConfig =
        if (preset == VideoQualityPreset.CUSTOM) {
            copy(qualityPreset = VideoQualityPreset.CUSTOM)
        } else {
            copy(
                qualityPreset = preset,
                resolution = preset.defaultResolution,
                targetFps = preset.defaultFps
            )
        }

    fun withTargetSizeBytes(bytes: Long): VideoCompressionConfig =
        copy(targetSizeBytes = bytes.coerceIn(MIN_TARGET_SIZE_BYTES, MAX_TARGET_SIZE_BYTES))

    fun withResolution(newResolution: VideoResolution): VideoCompressionConfig {
        val nextPreset = VideoQualityPreset.matching(newResolution, targetFps)
        return copy(qualityPreset = nextPreset, resolution = newResolution)
    }

    fun withTargetFps(fps: Float?): VideoCompressionConfig {
        if (fps != null && (fps.isNaN() || fps.isInfinite())) return this
        val sanitizedFps = fps?.coerceIn(MIN_FPS, MAX_FPS)
        val nextPreset = VideoQualityPreset.matching(resolution, sanitizedFps)
        return copy(qualityPreset = nextPreset, targetFps = sanitizedFps)
    }

    fun withCodec(newCodec: VideoCodec, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        if (!newCodec.isAllowed(enableAllCodecs, apiLevel)) return this

        val nextContainer = if (newCodec in container.supportedVideoCodecs(apiLevel)) {
            container
        } else {
            newCodec.preferredContainer()
        }

        val supportedAudio = nextContainer.supportedAudioCodecs(apiLevel)
        val nextAudio = when {
            audioCodec in supportedAudio -> audioCodec
            // Never silently mute an active audio track when a re-encodable audio codec is unavailable on this API
            !isAudioMuted && supportedAudio.none { it != VideoAudioCodec.MUTE } -> return this
            audioCodec == VideoAudioCodec.COPY && supportedAudio.any { it.requiresReencode } -> {
                supportedAudio.first { it.requiresReencode }
            }
            else -> supportedAudio.firstOrNull { it.requiresReencode } ?: VideoAudioCodec.MUTE
        }

        return copy(
            codec = newCodec,
            container = nextContainer,
            audioCodec = nextAudio
        )
    }

    fun withContainer(newContainer: VideoContainer, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        val supportedVideo = newContainer.supportedVideoCodecs(apiLevel)
            .filter { it.isAllowed(enableAllCodecs, apiLevel) }
        if (supportedVideo.isEmpty()) return this

        val nextVideo = if (codec in supportedVideo) codec else supportedVideo.first()
        val supportedAudio = newContainer.supportedAudioCodecs(apiLevel)
        val nextAudio = when {
            audioCodec in supportedAudio -> audioCodec
            !isAudioMuted && supportedAudio.none { it != VideoAudioCodec.MUTE } -> return this
            else -> supportedAudio.firstOrNull { it.requiresReencode } ?: VideoAudioCodec.MUTE
        }
        return copy(codec = nextVideo, container = newContainer, audioCodec = nextAudio)
    }

    fun withAudioCodec(newAudioCodec: VideoAudioCodec, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        if (apiLevel < newAudioCodec.minApiLevel) return this

        val nextContainer = if (newAudioCodec in container.supportedAudioCodecs(apiLevel)) {
            container
        } else {
            VideoContainer.entries.firstOrNull { c ->
                codec in c.supportedVideoCodecs(apiLevel) && newAudioCodec in c.supportedAudioCodecs(apiLevel)
            } ?: return this
        }

        return copy(container = nextContainer, audioCodec = newAudioCodec)
    }

    // Never silently unmutes or overrides COPY when storing user's preferred bitrate/volume
    fun withAudioBitrateKbps(kbps: Int): VideoCompressionConfig =
        copy(audioBitrateKbps = kbps.coerceIn(MIN_AUDIO_BITRATE_KBPS, MAX_AUDIO_BITRATE_KBPS))

    fun withAudioVolume(volume: Float): VideoCompressionConfig {
        if (volume.isNaN() || volume.isInfinite()) return this
        return copy(audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME))
    }

    fun withEnableAllCodecs(enabled: Boolean, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        val nextCodec = if (!enabled && codec != VideoCodec.H264 && codec != VideoCodec.H265) {
            VideoCodec.H264
        } else {
            codec
        }
        return copy(enableAllCodecs = enabled).withCodec(nextCodec, apiLevel)
    }

    companion object {
        const val MIN_TARGET_SIZE_BYTES = 1024L
        const val MAX_TARGET_SIZE_BYTES = 100_000_000_000L // 100 GB decimal
        const val DEFAULT_TARGET_SIZE_BYTES = 20_000_000L // 20 MB decimal
        const val MIN_FPS = 1f
        const val MAX_FPS = 240f
        const val DEFAULT_AUDIO_BITRATE_KBPS = 96
        const val MIN_AUDIO_BITRATE_KBPS = 16
        const val MAX_AUDIO_BITRATE_KBPS = 512
        const val DEFAULT_AUDIO_VOLUME = 1.0f
        const val MIN_AUDIO_VOLUME = 0.25f
        const val MAX_AUDIO_VOLUME = 2.0f
    }
}

// =============================================================================
// STRICT RFC-8259 PURE-KOTLIN PRESET CODEC & CONCURRENCY-SAFE REPOSITORY
// =============================================================================

interface KeyValueStorage {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String): Boolean
    suspend fun remove(key: String): Boolean
}

interface PresetLogger {
    fun warn(message: String, throwable: Throwable? = null)
}

object NoOpPresetLogger : PresetLogger {
    override fun warn(message: String, throwable: Throwable?) = Unit
}

object PresetJsonCodec {
    fun encode(presets: List<VideoTargetPreset>): String {
        val sb = StringBuilder("[")
        presets.forEachIndexed { idx, p ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":\"").append(escapeJson(p.id)).append("\",")
            sb.append("\"name\":\"").append(escapeJson(p.name)).append("\",")
            sb.append("\"sizeBytes\":").append(p.sizeBytes).append(',')
            sb.append("\"isDefault\":").append(p.isDefault).append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Returns:
     * - emptyList() if input is an explicit valid empty array "[]"
     * - null if input is structurally corrupted / truncated / all entries malformed
     * - non-empty list of valid decoded entries otherwise
     */
    fun decode(raw: String, logger: PresetLogger = NoOpPresetLogger): List<VideoTargetPreset>? {
        val blocks = extractObjectBlocksStrict(raw, logger) ?: return null
        if (blocks.isEmpty()) return emptyList()

        val result = mutableListOf<VideoTargetPreset>()
        val seenIds = HashSet<String>()
        for (block in blocks) {
            try {
                val fields = parseFlatObjectStrict(block)
                val id = (fields["id"] as? String)?.trim().orEmpty()
                val name = (fields["name"] as? String)?.trim().orEmpty()
                val sizeBytes = (fields["sizeBytes"] as? Long) ?: -1L
                val isDefault = (fields["isDefault"] as? Boolean) ?: false

                val preset = VideoTargetPreset(
                    id = id,
                    name = name,
                    sizeBytes = sizeBytes,
                    isDefault = isDefault
                )
                if (seenIds.add(preset.id)) {
                    result.add(preset)
                } else {
                    logger.warn("Duplicate preset id '${preset.id}' ignored during decode")
                }
            } catch (e: Exception) {
                logger.warn("Skipping malformed preset entry", e)
            }
        }
        return if (result.isEmpty()) null else result
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", c.code))
                    } else {
                        append(c)
                    }
                }
            }
        }
    }

    private fun extractObjectBlocksStrict(raw: String, logger: PresetLogger): List<String>? {
        val n = raw.length
        var i = 0
        fun skipWs() { while (i < n && raw[i].isWhitespace()) i++ }

        skipWs()
        if (i >= n || raw[i] != '[') {
            logger.warn("Preset JSON root does not start with '['")
            return null
        }
        i++ // skip '['
        skipWs()

        val blocks = mutableListOf<String>()
        if (i < n && raw[i] == ']') {
            i++
            skipWs()
            if (i != n) {
                logger.warn("Trailing garbage after empty preset array")
                return null
            }
            return emptyList()
        }

        while (i < n) {
            skipWs()
            if (i >= n || raw[i] != '{') {
                logger.warn("Expected '{' in preset JSON array at index $i")
                return null
            }
            val start = i
            var depth = 0
            var inString = false
            var escaped = false

            while (i < n) {
                val c = raw[i++]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                }
            }
            if (depth != 0 || inString) {
                logger.warn("Truncated or unclosed object in preset JSON")
                return null
            }
            blocks.add(raw.substring(start, i))
            skipWs()
            if (i >= n) {
                logger.warn("Missing closing ']' in preset JSON")
                return null
            }
            if (raw[i] == ',') {
                i++
            } else if (raw[i] == ']') {
                i++
                skipWs()
                if (i != n) {
                    logger.warn("Trailing characters after closing ']' in preset JSON")
                    return null
                }
                return blocks
            } else {
                logger.warn("Expected ',' or ']' in preset JSON at index $i")
                return null
            }
        }
        logger.warn("Unexpected end of preset JSON")
        return null
    }

    private fun parseFlatObjectStrict(block: String): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var i = 0
        val n = block.length

        fun skipWs() { while (i < n && block[i].isWhitespace()) i++ }

        fun readString(): String {
            if (i >= n || block[i] != '"') throw IllegalArgumentException("Expected '\"' at $i")
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = block[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= n) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = block[i++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > n) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = block.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        fun skipNestedValue() {
            val open = block[i]
            val close = if (open == '{') '}' else ']'
            var depth = 0
            var inStr = false
            var esc = false
            while (i < n) {
                val ch = block[i++]
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                } else {
                    if (ch == '"') inStr = true
                    else if (ch == open) depth++
                    else if (ch == close && --depth == 0) return
                }
            }
            throw IllegalArgumentException("Unterminated nested JSON value")
        }

        fun checkBoundary() {
            if (i < n && !block[i].isWhitespace() && block[i] != ',' && block[i] != '}') {
                throw IllegalArgumentException("Unexpected trailing character '${block[i]}' at $i")
            }
        }

        skipWs()
        if (i >= n || block[i] != '{') throw IllegalArgumentException("Expected '{'")
        i++
        skipWs()
        if (i < n && block[i] == '}') return map

        while (i < n) {
            skipWs()
            val key = readString()
            skipWs()
            if (i >= n || block[i] != ':') throw IllegalArgumentException("Expected ':' after $key")
            i++
            skipWs()
            if (i >= n) throw IllegalArgumentException("Missing value for $key")

            val value: Any? = when {
                block[i] == '"' -> readString()
                block[i] == '{' || block[i] == '[' -> {
                    skipNestedValue()
                    null
                }
                block.startsWith("true", i) -> {
                    i += 4
                    checkBoundary()
                    true
                }
                block.startsWith("false", i) -> {
                    i += 5
                    checkBoundary()
                    false
                }
                block.startsWith("null", i) -> {
                    i += 4
                    checkBoundary()
                    null
                }
                block[i] == '-' || block[i].isDigit() -> {
                    val start = i
                    while (i < n && (block[i].isDigit() || block[i] in setOf('-', '+', '.', 'e', 'E'))) i++
                    checkBoundary()
                    val numStr = block.substring(start, i)
                    numStr.toLongOrNull() ?: numStr.toDoubleOrNull()?.toLong()
                        ?: throw IllegalArgumentException("Invalid number '$numStr'")
                }
                else -> throw IllegalArgumentException("Unsupported JSON token at $i")
            }
            map[key] = value
            skipWs()
            if (i >= n) throw IllegalArgumentException("Unexpected end of object")
            if (block[i] == ',') {
                i++
            } else if (block[i] == '}') {
                i++
                skipWs()
                if (i != n) throw IllegalArgumentException("Trailing content after '}'")
                return map
            } else {
                throw IllegalArgumentException("Expected ',' or '}' at $i")
            }
        }
        throw IllegalArgumentException("Unterminated object")
    }
}

class VideoPresetRepository(
    private val storage: KeyValueStorage,
    private val logger: PresetLogger = NoOpPresetLogger
) {
    private val mutex = Mutex()

    suspend fun loadPresets(): List<VideoTargetPreset> = mutex.withLock {
        val raw = try {
            storage.getString(KEY_PRESETS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed reading presets", e)
            return@withLock DEFAULT_PRESETS
        }

        if (raw == null) return@withLock DEFAULT_PRESETS
        if (raw.isBlank()) {
            logger.warn("Stored presets string was blank; reverting to defaults")
            return@withLock DEFAULT_PRESETS
        }

        return@withLock PresetJsonCodec.decode(raw, logger) ?: DEFAULT_PRESETS
    }

    suspend fun savePresets(presets: List<VideoTargetPreset>): Boolean = mutex.withLock {
        val normalized = presets.map {
            it.copy(id = it.id.trim(), name = it.name.trim())
        }.distinctBy { it.id }

        try {
            val committed = storage.putString(KEY_PRESETS, PresetJsonCodec.encode(normalized))
            if (!committed) logger.warn("Storage commit returned false when saving presets")
            committed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed saving presets", e)
            false
        }
    }

    suspend fun resetDefaults(): List<VideoTargetPreset> = mutex.withLock {
        try {
            val removed = storage.remove(KEY_PRESETS)
            if (!removed) logger.warn("Storage remove returned false during preset reset")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed clearing persisted presets", e)
        }
        DEFAULT_PRESETS
    }

    companion object {
        private const val KEY_PRESETS = "target_presets_json"
        private const val MB_DECIMAL = 1_000_000L
        private const val GB_DECIMAL = 1_000_000_000L

        val DEFAULT_PRESETS: List<VideoTargetPreset> = listOf(
            VideoTargetPreset("github", "GitHub", 10L * MB_DECIMAL, true),
            VideoTargetPreset("discord", "Discord", 20L * MB_DECIMAL, true),
            VideoTargetPreset("email", "Email (25MB MIME)", 18L * MB_DECIMAL, true),
            VideoTargetPreset("nitro_basic", "Stories / Nitro Basic", 50L * MB_DECIMAL, true),
            VideoTargetPreset("whatsapp_video", "WhatsApp Video", 100L * MB_DECIMAL, true),
            VideoTargetPreset("messenger", "Messenger / Bluesky", 100L * MB_DECIMAL, true),
            VideoTargetPreset("reels", "Nitro / Reels", 500L * MB_DECIMAL, true),
            VideoTargetPreset("twitter", "Twitter / X", 512L * MB_DECIMAL, true),
            VideoTargetPreset("telegram_doc", "Telegram / WA Doc", 2L * GB_DECIMAL, true),
            VideoTargetPreset("tg_prem", "TG Premium", 4L * GB_DECIMAL, true),
            VideoTargetPreset("x_prem", "X Premium", 8L * GB_DECIMAL, true)
        )
    }
}

/**
 * Single-process SharedPreferences storage adapter with lazy off-main-thread initialization.
 * Matches ProcessingForegroundService running in the same default app process.
 */
class SharedPreferencesKeyValueStorage(
    context: Context,
    private val prefsName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : KeyValueStorage {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    override suspend fun getString(key: String): String? = withContext(ioDispatcher) {
        prefs.getString(key, null)
    }

    override suspend fun putString(key: String, value: String): Boolean = withContext(ioDispatcher) {
        prefs.edit().putString(key, value).commit()
    }

    override suspend fun remove(key: String): Boolean = withContext(ioDispatcher) {
        prefs.edit().remove(key).commit()
    }
}

object AndroidPresetLogger : PresetLogger {
    private const val TAG = "VideoPresetStore"
    override fun warn(message: String, throwable: Throwable?) {
        Log.w(TAG, message, throwable)
    }
}

object AndroidVideoPresetRepositoryFactory {
    fun create(
        context: Context,
        prefsName: String = "${context.packageName}.video_presets"
    ): VideoPresetRepository {
        return VideoPresetRepository(
            storage = SharedPreferencesKeyValueStorage(context, prefsName),
            logger = AndroidPresetLogger
        )
    }
}
