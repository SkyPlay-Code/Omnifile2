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
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
            if (normalized.isEmpty()) return null
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

    // Android framework MediaMuxer supports:
    // - MP4: H.264 (API 21+), H.265/HEVC (API 24+), AV1 (API 34+ baseline encoder)
    // - WebM: VP9 (API 24+), NOT AV1
    fun supportedVideoCodecs(apiLevel: Int = Int.MAX_VALUE): Set<VideoCodec> = when (this) {
        MP4 -> buildSet {
            if (apiLevel >= VideoCodec.H264.minApiLevel) add(VideoCodec.H264)
            if (apiLevel >= VideoCodec.H265.minApiLevel) add(VideoCodec.H265)
            if (apiLevel >= VideoCodec.AV1.minApiLevel) add(VideoCodec.AV1)
        }
        WEBM -> buildSet {
            if (apiLevel >= VideoCodec.VP9.minApiLevel) add(VideoCodec.VP9)
        }
    }

    // Android framework MediaMuxer supports AAC/passthrough in MP4, and Opus (API 29 encoder) in WebM
    fun supportedAudioCodecs(apiLevel: Int = Int.MAX_VALUE): Set<VideoAudioCodec> = when (this) {
        MP4 -> buildSet {
            if (apiLevel >= VideoAudioCodec.AAC.minApiLevel) add(VideoAudioCodec.AAC)
            if (apiLevel >= VideoAudioCodec.COPY.minApiLevel) add(VideoAudioCodec.COPY)
            if (apiLevel >= VideoAudioCodec.MUTE.minApiLevel) add(VideoAudioCodec.MUTE)
        }
        WEBM -> buildSet {
            if (apiLevel >= VideoAudioCodec.OPUS.minApiLevel) add(VideoAudioCodec.OPUS)
            if (apiLevel >= VideoAudioCodec.MUTE.minApiLevel) add(VideoAudioCodec.MUTE)
        }
    }

    fun defaultAudioCodec(apiLevel: Int = Int.MAX_VALUE): VideoAudioCodec {
        val supported = supportedAudioCodecs(apiLevel)
        return supported.firstOrNull { it.requiresReencode }
            ?: supported.firstOrNull()
            ?: VideoAudioCodec.MUTE
    }

    companion object {
        fun fromId(id: String?): VideoContainer? {
            val normalized = id?.trim() ?: return null
            if (normalized.isEmpty()) return null
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
    H265("h265", "H.265 (HEVC)", "~35% smaller at equal quality (Android 7+)", "video/hevc", 24, 1.35f),
    VP9("vp9", "VP9", "Open royalty-free web codec (Android 7+)", "video/x-vnd.on2.vp9", 24, 1.30f),
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
            if (normalized.isEmpty()) return null
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
    AAC("aac", "AAC", "Universal compatibility (MP4)", true, 21),
    OPUS("opus", "Opus", "High efficiency (WebM / Android 10+)", true, 29),
    COPY("copy", "Copy", "Passthrough original audio (MP4)", false, 21),
    MUTE("mute", "Mute", "Strip audio track completely", false, 21);

    companion object {
        fun fromId(id: String?): VideoAudioCodec? {
            val normalized = id?.trim() ?: return null
            if (normalized.isEmpty()) return null
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
            if (normalized.isEmpty()) return null
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
        require(targetFps == null || (targetFps.isFinite() && targetFps in MIN_FPS..MAX_FPS)) {
            "targetFps must be null or finite in $MIN_FPS..$MAX_FPS (was $targetFps)"
        }
        require(audioBitrateKbps in MIN_AUDIO_BITRATE_KBPS..MAX_AUDIO_BITRATE_KBPS) {
            "audioBitrateKbps out of bounds (was $audioBitrateKbps)"
        }
        require(audioVolume.isFinite() && audioVolume in MIN_AUDIO_VOLUME..MAX_AUDIO_VOLUME) {
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

    fun isSupportedOn(apiLevel: Int): Boolean =
        codec.isAllowed(enableAllCodecs, apiLevel) &&
            codec in container.supportedVideoCodecs(apiLevel) &&
            audioCodec in container.supportedAudioCodecs(apiLevel) &&
            apiLevel >= audioCodec.minApiLevel

    fun sanitizedForApiLevel(apiLevel: Int): VideoCompressionConfig {
        if (isSupportedOn(apiLevel)) return this
        val targetCodec = if (codec.isAllowed(enableAllCodecs, apiLevel)) {
            codec
        } else {
            VideoCodec.H264
        }
        if (!targetCodec.isAllowed(enableAllCodecs, apiLevel)) return this

        val nextContainer = if (targetCodec in container.supportedVideoCodecs(apiLevel)) {
            container
        } else {
            targetCodec.preferredContainer()
        }
        val nextAudio = resolveAudioCodec(nextContainer.supportedAudioCodecs(apiLevel))
        if (nextAudio != null && targetCodec in nextContainer.supportedVideoCodecs(apiLevel)) {
            return copy(codec = targetCodec, container = nextContainer, audioCodec = nextAudio)
        }

        // Fallback to H.264/MP4 if the current codec/container cannot preserve non-muted audio on this API
        val fallbackContainer = VideoCodec.H264.preferredContainer()
        if (!VideoCodec.H264.isAllowed(enableAllCodecs, apiLevel)) return this
        val fallbackAudio = resolveAudioCodec(fallbackContainer.supportedAudioCodecs(apiLevel)) ?: return this
        return copy(codec = VideoCodec.H264, container = fallbackContainer, audioCodec = fallbackAudio)
    }

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
        if (newResolution == resolution) return this
        val nextPreset = VideoQualityPreset.matching(newResolution, targetFps)
        return copy(qualityPreset = nextPreset, resolution = newResolution)
    }

    fun withTargetFps(fps: Float?): VideoCompressionConfig {
        if (fps != null && !fps.isFinite()) return this
        val sanitizedFps = fps?.coerceIn(MIN_FPS, MAX_FPS)
        if (sanitizedFps == targetFps) return this
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
        if (newCodec !in nextContainer.supportedVideoCodecs(apiLevel)) return this

        val nextAudio = resolveAudioCodec(nextContainer.supportedAudioCodecs(apiLevel)) ?: return this
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
        val nextAudio = resolveAudioCodec(newContainer.supportedAudioCodecs(apiLevel)) ?: return this
        return copy(codec = nextVideo, container = newContainer, audioCodec = nextAudio)
    }

    fun withAudioCodec(newAudioCodec: VideoAudioCodec, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        if (apiLevel < newAudioCodec.minApiLevel) return this

        val nextContainer = if (
            codec in container.supportedVideoCodecs(apiLevel) &&
            newAudioCodec in container.supportedAudioCodecs(apiLevel)
        ) {
            container
        } else {
            VideoContainer.entries.firstOrNull { c ->
                codec in c.supportedVideoCodecs(apiLevel) &&
                    newAudioCodec in c.supportedAudioCodecs(apiLevel)
            } ?: return this
        }

        return copy(container = nextContainer, audioCodec = newAudioCodec)
    }

    // Never silently unmutes or overrides COPY when storing user's preferred bitrate/volume
    fun withAudioBitrateKbps(kbps: Int): VideoCompressionConfig =
        copy(audioBitrateKbps = kbps.coerceIn(MIN_AUDIO_BITRATE_KBPS, MAX_AUDIO_BITRATE_KBPS))

    fun withAudioVolume(volume: Float): VideoCompressionConfig {
        if (!volume.isFinite()) return this
        return copy(audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME))
    }

    fun withEnableAllCodecs(enabled: Boolean, apiLevel: Int = Int.MAX_VALUE): VideoCompressionConfig {
        if (enabled) return copy(enableAllCodecs = true)

        val targetCodec = if (codec.isAllowed(enableAllCodecs = false, apiLevel)) {
            codec
        } else {
            VideoCodec.H264
        }
        if (!targetCodec.isAllowed(enableAllCodecs = false, apiLevel)) return this

        val nextContainer = if (targetCodec in container.supportedVideoCodecs(apiLevel)) {
            container
        } else {
            targetCodec.preferredContainer()
        }
        if (targetCodec !in nextContainer.supportedVideoCodecs(apiLevel)) return this

        val nextAudio = resolveAudioCodec(nextContainer.supportedAudioCodecs(apiLevel)) ?: return this
        return copy(
            enableAllCodecs = false,
            codec = targetCodec,
            container = nextContainer,
            audioCodec = nextAudio
        )
    }

    private fun resolveAudioCodec(supportedAudio: Set<VideoAudioCodec>): VideoAudioCodec? {
        if (audioCodec in supportedAudio) return audioCodec
        // Never silently mute an active audio track when a non-mute codec is unavailable on this API
        if (!isAudioMuted && supportedAudio.none { it != VideoAudioCodec.MUTE }) return null
        return supportedAudio.firstOrNull { it.requiresReencode }
            ?: supportedAudio.firstOrNull { it != VideoAudioCodec.MUTE }
            ?: VideoAudioCodec.MUTE.takeIf { it in supportedAudio }
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
    private const val MAX_JSON_DEPTH = 64
    private val MIN_SIZE_BD = BigDecimal.valueOf(VideoCompressionConfig.MIN_TARGET_SIZE_BYTES)
    private val MAX_SIZE_BD = BigDecimal.valueOf(VideoCompressionConfig.MAX_TARGET_SIZE_BYTES)

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
     * - null if input is structurally corrupted / truncated / not an array of objects / all entries malformed
     * - non-empty list of valid decoded entries otherwise
     */
    fun decode(raw: String, logger: PresetLogger = NoOpPresetLogger): List<VideoTargetPreset>? {
        val rawObjects = try {
            StrictJsonParser(raw).parseRootPresetArray()
        } catch (e: Exception) {
            logger.warn("Preset JSON failed strict RFC-8259 parsing", e)
            return null
        }

        if (rawObjects.isEmpty()) return emptyList()

        val result = mutableListOf<VideoTargetPreset>()
        val seenIds = HashSet<String>()
        for (fields in rawObjects) {
            try {
                val id = (fields["id"] as? JsonVal.Str)?.value?.trim()
                    ?: throw IllegalArgumentException("Missing or non-string 'id'")
                val name = (fields["name"] as? JsonVal.Str)?.value?.trim()
                    ?: throw IllegalArgumentException("Missing or non-string 'name'")
                val sizeRaw = (fields["sizeBytes"] as? JsonVal.Num)?.raw
                    ?: throw IllegalArgumentException("Missing or non-numeric 'sizeBytes'")
                val sizeBytes = parseExactSizeBytes(sizeRaw)
                val isDefault = when (val v = fields["isDefault"]) {
                    null -> false
                    is JsonVal.Bool -> v.value
                    else -> throw IllegalArgumentException("Invalid non-boolean 'isDefault'")
                }

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

    private fun parseExactSizeBytes(numStr: String): Long {
        val bd = try {
            BigDecimal(numStr)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid numeric sizeBytes '$numStr'", e)
        }
        if (bd < MIN_SIZE_BD || bd > MAX_SIZE_BD) {
            throw IllegalArgumentException("sizeBytes out of bounds '$numStr'")
        }
        val normalized = bd.stripTrailingZeros()
        if (normalized.scale() > 0) {
            throw IllegalArgumentException("Fractional sizeBytes is not allowed '$numStr'")
        }
        return normalized.longValueExact()
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

    private sealed interface JsonVal {
        data class Str(val value: String) : JsonVal
        data class Num(val raw: String) : JsonVal
        data class Bool(val value: Boolean) : JsonVal
        data object Null : JsonVal
        data object Complex : JsonVal
    }

    private class StrictJsonParser(private val input: String) {
        private val len = input.length
        private var pos = 0

        fun parseRootPresetArray(): List<Map<String, JsonVal>> {
            skipWs()
            expect('[')
            skipWs()

            if (pos < len && input[pos] == ']') {
                pos++
                skipWs()
                if (pos != len) throw IllegalArgumentException("Trailing content after ']' at $pos")
                return emptyList()
            }

            val items = mutableListOf<Map<String, JsonVal>>()
            while (true) {
                skipWs()
                items.add(readObject(depth = 1))
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated root JSON array")
                when (input[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        skipWs()
                        if (pos != len) throw IllegalArgumentException("Trailing content after ']' at $pos")
                        return items
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readObject(depth: Int): Map<String, JsonVal> {
            if (depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds $MAX_JSON_DEPTH")
            expect('{')
            skipWs()

            val map = mutableMapOf<String, JsonVal>()
            if (pos < len && input[pos] == '}') {
                pos++
                return map
            }

            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated JSON object")
                when (input[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readValue(depth: Int): JsonVal {
            if (depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds $MAX_JSON_DEPTH")
            if (pos >= len) throw IllegalArgumentException("Unexpected end of JSON at $pos")

            return when (val ch = input[pos]) {
                '"' -> JsonVal.Str(readString())
                '{' -> {
                    skipObject(depth + 1)
                    JsonVal.Complex
                }
                '[' -> {
                    skipArray(depth + 1)
                    JsonVal.Complex
                }
                't' -> {
                    expectLiteral("true")
                    JsonVal.Bool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonVal.Bool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonVal.Null
                }
                '-', in '0'..'9' -> JsonVal.Num(readNumberToken())
                else -> throw IllegalArgumentException("Unexpected JSON token '$ch' at $pos")
            }
        }

        private fun skipObject(depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds $MAX_JSON_DEPTH")
            expect('{')
            skipWs()
            if (pos < len && input[pos] == '}') {
                pos++
                return
            }
            while (true) {
                skipWs()
                readString()
                skipWs()
                expect(':')
                skipWs()
                skipValue(depth + 1)
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated nested JSON object")
                when (input[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in nested object at $pos")
                }
            }
        }

        private fun skipArray(depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds $MAX_JSON_DEPTH")
            expect('[')
            skipWs()
            if (pos < len && input[pos] == ']') {
                pos++
                return
            }
            while (true) {
                skipWs()
                skipValue(depth + 1)
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated nested JSON array")
                when (input[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in nested array at $pos")
                }
            }
        }

        private fun skipValue(depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds $MAX_JSON_DEPTH")
            if (pos >= len) throw IllegalArgumentException("Unexpected end of nested JSON value")
            when (val ch = input[pos]) {
                '"' -> readString()
                '{' -> skipObject(depth + 1)
                '[' -> skipArray(depth + 1)
                't' -> expectLiteral("true")
                'f' -> expectLiteral("false")
                'n' -> expectLiteral("null")
                '-', in '0'..'9' -> readNumberToken()
                else -> throw IllegalArgumentException("Unexpected JSON token '$ch' at $pos")
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < len) {
                val c = input[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c.code < 0x20 -> throw IllegalArgumentException("Unescaped control character in JSON string")
                    c == '\\' -> {
                        if (pos >= len) throw IllegalArgumentException("Unterminated escape sequence")
                        when (val esc = input[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) throw IllegalArgumentException("Truncated unicode escape")
                                val codeUnit = (hexDigit(input[pos]) shl 12) or
                                    (hexDigit(input[pos + 1]) shl 8) or
                                    (hexDigit(input[pos + 2]) shl 4) or
                                    hexDigit(input[pos + 3])
                                sb.append(codeUnit.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hex character '$c' in unicode escape")
        }

        private fun readNumberToken(): String {
            val start = pos
            if (pos < len && input[pos] == '-') pos++
            if (pos >= len) throw IllegalArgumentException("Invalid JSON number at $start")

            if (input[pos] == '0') {
                pos++
            } else if (input[pos] in '1'..'9') {
                pos++
                while (pos < len && input[pos] in '0'..'9') pos++
            } else {
                throw IllegalArgumentException("Invalid JSON number at $start")
            }

            if (pos < len && input[pos] == '.') {
                pos++
                if (pos >= len || input[pos] !in '0'..'9') {
                    throw IllegalArgumentException("Expected fractional digit in JSON number at $pos")
                }
                while (pos < len && input[pos] in '0'..'9') pos++
            }

            if (pos < len && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++
                if (pos < len && (input[pos] == '+' || input[pos] == '-')) pos++
                if (pos >= len || input[pos] !in '0'..'9') {
                    throw IllegalArgumentException("Expected exponent digit in JSON number at $pos")
                }
                while (pos < len && input[pos] in '0'..'9') pos++
            }

            return input.substring(start, pos)
        }

        // Strict RFC-8259 whitespace: SP (0x20), HTAB (0x09), LF (0x0A), CR (0x0D)
        private fun skipWs() {
            while (pos < len) {
                when (input[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun expect(expected: Char) {
            if (pos >= len || input[pos] != expected) {
                throw IllegalArgumentException("Expected '$expected' at index $pos")
            }
            pos++
        }

        private fun expectLiteral(literal: String) {
            if (!input.regionMatches(pos, literal, 0, literal.length)) {
                throw IllegalArgumentException("Expected '$literal' at index $pos")
            }
            pos += literal.length
        }
    }
}

class VideoPresetRepository(
    private val storage: KeyValueStorage,
    private val logger: PresetLogger = NoOpPresetLogger
) {
    private val mutex = Mutex()

    suspend fun loadPresets(): List<VideoTargetPreset> = mutex.withLock {
        loadUnlocked()
    }

    suspend fun savePresets(presets: List<VideoTargetPreset>): Boolean = mutex.withLock {
        saveUnlocked(presets)
    }

    suspend fun updatePresets(
        transform: (List<VideoTargetPreset>) -> List<VideoTargetPreset>
    ): Boolean = mutex.withLock {
        val current = loadUnlocked()
        val updated = transform(current)
        saveUnlocked(updated)
    }

    suspend fun resetDefaults(): Boolean = mutex.withLock {
        try {
            val removed = storage.remove(KEY_PRESETS)
            if (!removed) logger.warn("Storage remove returned false during preset reset")
            removed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed clearing persisted presets", e)
            false
        }
    }

    private suspend fun loadUnlocked(): List<VideoTargetPreset> {
        val raw = try {
            storage.getString(KEY_PRESETS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClassCastException) {
            logger.warn("Stored presets had unexpected preference type; clearing corrupted key", e)
            clearCorruptedKeySafely()
            return DEFAULT_PRESETS
        } catch (e: Exception) {
            logger.warn("Failed reading presets", e)
            return DEFAULT_PRESETS
        }

        if (raw == null) return DEFAULT_PRESETS
        if (raw.isBlank()) {
            logger.warn("Stored presets string was blank; clearing and reverting to defaults")
            clearCorruptedKeySafely()
            return DEFAULT_PRESETS
        }

        val decoded = PresetJsonCodec.decode(raw, logger)
        if (decoded == null) {
            logger.warn("Stored presets JSON was corrupted; clearing and reverting to defaults")
            clearCorruptedKeySafely()
            return DEFAULT_PRESETS
        }
        return decoded
    }

    private suspend fun saveUnlocked(presets: List<VideoTargetPreset>): Boolean {
        val deduplicated = LinkedHashMap<String, VideoTargetPreset>(presets.size)
        for (preset in presets) {
            if (deduplicated.containsKey(preset.id)) {
                logger.warn("Duplicate preset id '${preset.id}' encountered during save; keeping latest")
            }
            deduplicated[preset.id] = preset
        }

        return try {
            val committed = storage.putString(KEY_PRESETS, PresetJsonCodec.encode(deduplicated.values.toList()))
            if (!committed) logger.warn("Storage commit returned false when saving presets")
            committed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed saving presets", e)
            false
        }
    }

    private suspend fun clearCorruptedKeySafely() {
        try {
            storage.remove(KEY_PRESETS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed removing corrupted preset entry", e)
        }
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
    init {
        require(prefsName.isNotBlank() && '/' !in prefsName && '\\' !in prefsName && ".." !in prefsName) {
            "Invalid SharedPreferences name: '$prefsName'"
        }
    }

    private val appContext: Context = context.applicationContext ?: context
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
    private val repositories = ConcurrentHashMap<String, VideoPresetRepository>()

    fun create(
        context: Context,
        prefsName: String = defaultPrefsName(context)
    ): VideoPresetRepository {
        repositories[prefsName]?.let { return it }
        val created = VideoPresetRepository(
            storage = SharedPreferencesKeyValueStorage(context, prefsName),
            logger = AndroidPresetLogger
        )
        return repositories.putIfAbsent(prefsName, created) ?: created
    }

    private fun defaultPrefsName(context: Context): String {
        val pkg = context.packageName?.takeIf { it.isNotBlank() } ?: "app"
        return "$pkg.video_presets"
    }
}
