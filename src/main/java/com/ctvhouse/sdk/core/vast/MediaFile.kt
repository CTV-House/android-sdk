package com.ctvhouse.sdk.core.vast

internal data class MediaFile(
    val url: String,
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val delivery: String = "",
    val bitrate: Int = 0,
    val apiFramework: String = "",
) {
    val area: Long get() = width.toLong() * height.toLong()

    val isVpaid: Boolean
        get() = apiFramework.equals("VPAID", ignoreCase = true)

    /** Lower-case URL path — query and fragment must not decide the container. */
    internal val path: String
        get() = url.substringBefore('#').substringBefore('?').lowercase()

    val isVideo: Boolean
        get() {
            val type = mimeType.lowercase()
            if (type.startsWith("video/")) return true
            // Some tags omit type — infer from common video extensions.
            return path.endsWithAny(".mp4", ".webm", ".mov", ".m4v", ".3gp", ".mkv", ".ogv")
        }

    val isAudio: Boolean
        get() {
            val type = mimeType.lowercase()
            if (type.startsWith("audio/")) return true
            return path.endsWithAny(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".wav", ".flac")
        }

    val isStreamingManifest: Boolean
        get() {
            val type = mimeType.lowercase()
            return type.contains("mpegurl") ||
                type.contains("dash+xml") ||
                type.contains("x-mpegurl") ||
                path.endsWithAny(".m3u8", ".mpd")
        }

    val isProgressive: Boolean
        get() = delivery.isEmpty() ||
            delivery.equals("progressive", ignoreCase = true)
}

/**
 * Linear MediaFile selection for formats (not a parser).
 *
 * Prefers progressive playable creatives Exo can open directly:
 * 1) progressive **video** (any video MIME / known video URL) — best quality
 * 2) progressive **audio** (any audio MIME / known audio URL)
 * Skips VPAID and streaming manifests (HLS/DASH) when a progressive option exists.
 * Among equals: larger area, then bitrate; container preference mp4 > webm > other (video),
 * aac/m4a/mp3 preference for audio — not an mp4-only world.
 */
internal object MediaFiles {

    fun selectBest(files: List<MediaFile>): MediaFile? {
        val playable = playable(files)
        if (playable.isEmpty()) return null

        val progressiveVideo = playable.filter { it.isVideo && it.isProgressive }
        if (progressiveVideo.isNotEmpty()) {
            return progressiveVideo.maxWithOrNull(videoComparator)
        }
        val anyVideo = playable.filter { it.isVideo }
        if (anyVideo.isNotEmpty()) {
            return anyVideo.maxWithOrNull(videoComparator)
        }
        return selectAudio(playable)
    }

    /**
     * Dedicated audio under a companion still. Video files are ignored — no
     * soundtrack is taken from a video MediaFile.
     */
    fun selectSoundtrack(files: List<MediaFile>): MediaFile? = selectAudio(playable(files))

    fun selectBestUrl(files: List<MediaFile>): String? = selectBest(files)?.url

    fun selectSoundtrackUrl(files: List<MediaFile>): String? = selectSoundtrack(files)?.url

    private fun playable(files: List<MediaFile>): List<MediaFile> = files.filter {
        !it.isVpaid && it.url.startsWith("http") && !it.isStreamingManifest
    }

    private fun selectAudio(playable: List<MediaFile>): MediaFile? {
        val progressiveAudio = playable.filter { it.isAudio && it.isProgressive }
        if (progressiveAudio.isNotEmpty()) {
            return progressiveAudio.maxWithOrNull(audioComparator)
        }
        val anyAudio = playable.filter { it.isAudio }
        if (anyAudio.isNotEmpty()) {
            return anyAudio.maxWithOrNull(audioComparator)
        }
        return null
    }

    private fun videoContainerRank(file: MediaFile): Int {
        val type = file.mimeType.lowercase()
        val path = file.path
        return when {
            type.contains("mp4") || path.endsWithAny(".mp4", ".m4v") -> 3
            type.contains("webm") || path.endsWith(".webm") -> 2
            type.contains("3gpp") || path.endsWith(".3gp") -> 1
            else -> 0
        }
    }

    private fun audioContainerRank(file: MediaFile): Int {
        val type = file.mimeType.lowercase()
        val path = file.path
        return when {
            type.contains("mp4") || type.contains("aac") || type.contains("m4a") ||
                path.endsWithAny(".m4a", ".aac") -> 3
            type.contains("mpeg") || type.contains("mp3") || path.endsWith(".mp3") -> 2
            type.contains("ogg") || path.endsWithAny(".ogg", ".oga") -> 1
            else -> 0
        }
    }

    private val videoComparator = compareBy<MediaFile>(
        { videoContainerRank(it) },
        { it.area },
        { it.width },
        { it.height },
        { it.bitrate },
    )

    private val audioComparator = compareBy<MediaFile>(
        { audioContainerRank(it) },
        { it.bitrate },
    )
}

private fun String.endsWithAny(vararg suffixes: String): Boolean =
    suffixes.any { endsWith(it) }
