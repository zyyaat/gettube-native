package com.junkfood.seal.util

import android.util.Log
import com.junkfood.seal.util.VideoInfo
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpeRequest
import org.schabi.newpipe.extractor.downloader.Response as NpeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * GetTube: native InnerTube fast path for video info.
 *
 * Talks to YouTube's InnerTube player API directly (the same mechanism
 * NewPipe-class apps use) via NewPipeExtractor — one or two HTTP requests,
 * no Python process, no yt-dlp, and most importantly no QuickJS
 * signature-challenge solving. Measured at ~2-4s end-to-end with the full
 * quality list (video up to 4K + audio), versus 15-20s+ for a yt-dlp
 * extraction on-device.
 *
 * The produced [VideoInfo] mirrors what the yt-dlp JSON would carry, and the
 * synthesized raw JSON is yt-dlp-compatible so the existing
 * `--load-info-json` download path works unchanged (verified against both
 * the engine-bundled yt-dlp 2025.11.12 and current stable 2026.10.21).
 *
 * Every failure path (live streams, age-gated videos, extractor breakage)
 * surfaces as Result.failure so the caller falls back to yt-dlp.
 */
object NewPipeVideoInfoFetcher {

    private const val TAG = "NewPipeFetcher"

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val initialized = AtomicBoolean(false)

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .callTimeout(Duration.ofSeconds(45))
            .build()
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: NpeRequest): NpeResponse {
            val builder = Request.Builder().url(request.url())
            request.headers().forEach { (name, values) ->
                values.firstOrNull()?.let { builder.header(name, it) }
            }
            if (request.header("User-Agent") == null) {
                builder.header("User-Agent", DEFAULT_UA)
            }
            val data = request.dataToSend()
            when {
                data != null ->
                    builder.post(
                        data.toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                request.httpMethod().equals("HEAD", ignoreCase = true) -> builder.head()
                else -> builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                return NpeResponse(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    body,
                    request.url(),
                )
            }
        }

        private fun NpeRequest.header(name: String): String? =
            headers()[name]?.firstOrNull()
    }

    @Synchronized
    private fun ensureInit() {
        if (!initialized.get()) {
            NewPipe.init(OkHttpDownloader(okHttpClient))
            initialized.set(true)
        }
    }

    /** True only for direct YouTube video URLs (watch / youtu.be / shorts). */
    fun isYouTubeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("/playlist")) return false
        val host =
            runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        val isYouTubeHost =
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        val isVideoPath =
            lower.contains("watch?v=") || lower.contains("youtu.be/") ||
                lower.contains("/shorts/")
        return isYouTubeHost && isVideoPath
    }

    /**
     * Fetches title/metadata/qualities natively. Returns the [VideoInfo] the
     * quality sheet consumes plus a yt-dlp-compatible raw JSON string for the
     * download path's `--load-info-json` reuse.
     */
    fun fetchVideoInfo(url: String): Result<Pair<VideoInfo, String>> = runCatching {
        ensureInit()
        val startedAt = System.currentTimeMillis()
        val service = ServiceList.YouTube
        val extractor =
            service.getStreamExtractor(service.streamLHFactory.fromUrl(url))
        extractor.fetchPage()

        val durationSec = runCatching { extractor.length }.getOrDefault(0L)
        if (durationSec <= 0L) {
            throw IllegalStateException("Live or unknown-length stream; deferring to yt-dlp")
        }
        val muxed = extractor.videoStreams
        val videoOnly = extractor.videoOnlyStreams
        val audioOnly = extractor.audioStreams
        if (muxed.isEmpty() && videoOnly.isEmpty() && audioOnly.isEmpty()) {
            throw IllegalStateException("No streams returned by InnerTube")
        }

        val formats = ArrayList<Format>()
        val jsonFormats = ArrayList<JsonObject>()

        fun addFormat(
            formatId: String,
            streamUrl: String?,
            ext: String,
            formatNote: String?,
            resolution: String?,
            height: Int,
            fps: Int,
            vcodec: String?,
            acodec: String?,
            bitrate: Int,
            abr: Int,
            contentLength: Long,
            approxDurationMs: Long,
        ) {
            if (streamUrl.isNullOrEmpty()) return
            val exactSize =
                if (contentLength > 0) contentLength.toDouble() else null
            val approxSize =
                if (exactSize != null) null
                else if (bitrate > 0 && approxDurationMs > 0)
                    bitrate.toLong() * approxDurationMs / 8000L
                else null

            formats +=
                Format(
                    formatId = formatId,
                    formatNote = formatNote,
                    ext = ext,
                    acodec = acodec ?: "none",
                    vcodec = vcodec ?: "none",
                    url = streamUrl,
                    height = if (height > 0) height.toDouble() else null,
                    fps = if (fps > 0) fps.toDouble() else null,
                    format = formatNote,
                    resolution = resolution,
                    vbr = if (bitrate > 0) bitrate / 1000.0 else null,
                    abr = if (abr > 0) abr.toDouble() else null,
                    tbr = if (bitrate > 0) bitrate / 1000.0 else null,
                    fileSize = exactSize,
                    fileSizeApprox = approxSize?.toDouble(),
                )
            jsonFormats +=
                buildJsonObject {
                    put("format_id", formatId)
                    put("url", streamUrl)
                    put("ext", ext)
                    put("protocol", "https")
                    formatNote?.let { put("format_note", it) }
                    resolution?.let { put("resolution", it) }
                    if (height > 0) put("height", height)
                    if (fps > 0) put("fps", fps)
                    put("vcodec", vcodec ?: "none")
                    put("acodec", acodec ?: "none")
                    if (bitrate > 0) put("tbr", bitrate / 1000.0)
                    if (abr > 0) put("abr", abr.toDouble())
                    exactSize?.let { put("filesize", it) }
                    approxSize?.let { put("filesize_approx", it.toDouble()) }
                }
        }

        fun videoCodecPart(codec: String?, index: Int): String {
            if (codec.isNullOrEmpty()) return "unknown"
            val parts = codec.split(",".toRegex()).map { it.trim() }
            return if (index < parts.size) parts[index] else "unknown"
        }

        for (s in muxed) {
            val itag: ItagItem = s.itagItem ?: continue
            val ext = s.format?.suffix ?: continue
            val codec = itag.codec
            addFormat(
                formatId = s.id,
                streamUrl = s.content ?: s.url,
                ext = ext,
                formatNote = s.resolution,
                resolution = s.resolution,
                height = s.height,
                fps = itag.fps,
                vcodec = videoCodecPart(codec, 0),
                acodec = videoCodecPart(codec, 1),
                bitrate = itag.bitrate,
                abr = 0,
                contentLength = itag.contentLength,
                approxDurationMs = itag.approxDurationMs,
            )
        }
        // yt-dlp lists formats ascending by quality; the quality page relies
        // on that ordering, so reverse the descending NPE lists to match.
        for (s in videoOnly.reversed()) {
            val itag: ItagItem = s.itagItem ?: continue
            val ext = s.format?.suffix ?: continue
            addFormat(
                formatId = s.id,
                streamUrl = s.content ?: s.url,
                ext = ext,
                formatNote = s.resolution,
                resolution = s.resolution,
                height = s.height,
                fps = itag.fps,
                vcodec = itag.codec,
                acodec = "none",
                bitrate = itag.bitrate,
                abr = 0,
                contentLength = itag.contentLength,
                approxDurationMs = itag.approxDurationMs,
            )
        }
        for (s in audioOnly.reversed()) {
            val itag: ItagItem = s.itagItem ?: continue
            val ext = s.format?.suffix ?: continue
            val abr =
                if (itag.averageBitrate > 0) itag.averageBitrate
                else itag.bitrate / 1000
            addFormat(
                formatId = s.id,
                streamUrl = s.content ?: s.url,
                ext = ext,
                formatNote = "$abr kbps",
                resolution = "audio only",
                height = 0,
                fps = 0,
                vcodec = "none",
                acodec = itag.codec,
                bitrate = itag.bitrate,
                abr = abr,
                contentLength = itag.contentLength,
                approxDurationMs = itag.approxDurationMs,
            )
        }

        if (formats.isEmpty()) throw IllegalStateException("No usable formats mapped")

        val thumbnailUrl =
            runCatching {
                extractor.thumbnails
                    .maxByOrNull { it.height }
                    ?.url
            }.getOrNull().orEmpty()

        val subtitleMap =
            runCatching {
                extractor.getSubtitles(MediaFormat.VTT)
                    .groupBy(
                        { it.languageTag },
                        { stream ->
                            SubtitleFormat(
                                ext = stream.extension,
                                url = stream.content ?: stream.url,
                                name = stream.displayLanguageName,
                            )
                        },
                    )
            }.getOrDefault(emptyMap())

        val chapters =
            runCatching {
                extractor.streamSegments.map {
                    Chapter(
                        title = it.title,
                        startTime = it.startTimeSeconds.toDouble(),
                        endTime = null,
                    )
                }
            }.getOrNull()

        val info =
            VideoInfo(
                id = extractor.id,
                title = extractor.name,
                formats = formats,
                thumbnail = thumbnailUrl,
                description = runCatching { extractor.description?.content }.getOrNull(),
                uploader = runCatching { extractor.uploaderName }.getOrNull(),
                duration = durationSec.toDouble(),
                viewCount = runCatching { extractor.viewCount }.getOrNull(),
                webpageUrl = runCatching { extractor.url }.getOrNull() ?: url,
                originalUrl = url,
                extractor = "youtube",
                extractorKey = "Youtube",
                chapters = chapters,
                subtitles = subtitleMap,
                liveStatus = "not_live",
            )

        val rawJson =
            buildJsonObject {
                put("_type", "video")
                put("id", info.id)
                put("title", info.title)
                info.uploader?.let { put("uploader", it) }
                put("duration", durationSec)
                info.viewCount?.let { put("view_count", it) }
                put("extractor", "youtube")
                put("extractor_key", "Youtube")
                put("webpage_url", info.webpageUrl ?: url)
                put("original_url", url)
                if (thumbnailUrl.isNotEmpty()) put("thumbnail", thumbnailUrl)
                putJsonArray("formats") { jsonFormats.forEach { add(it) } }
            }.toString()

        Log.d(TAG, "native InnerTube fetch took ${System.currentTimeMillis() - startedAt}ms")
        info to rawJson
    }
}
