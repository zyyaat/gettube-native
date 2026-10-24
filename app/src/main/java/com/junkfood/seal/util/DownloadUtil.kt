package com.junkfood.seal.util

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.Downloader
import com.junkfood.seal.Downloader.onProcessEnded
import com.junkfood.seal.Downloader.onProcessStarted
import com.junkfood.seal.Downloader.onTaskEnded
import com.junkfood.seal.Downloader.onTaskError
import com.junkfood.seal.Downloader.onTaskStarted
import com.junkfood.seal.Downloader.toNotificationId
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.FileUtil.moveFilesToSdcard
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

object DownloadUtil {

    object CookieScheme {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private const val TAG = "DownloadUtil"

    const val SEARCH_PROCESS_ID = "ytsearch"

    // GetTube: the quality sheet fetches the video's full info (yt-dlp -J)
    // before the user picks a quality; keep the freshest results around so the
    // download engine reuses them instead of fetching the same JSON twice.
    // The raw JSON is kept as well: the download run hands it back to yt-dlp
    // via --load-info-json and skips its whole extraction phase at start-up.
    private const val INFO_CACHE_LIMIT = 24
    private const val INFO_CACHE_TTL_MS = 10 * 60 * 1000L

    private class CachedVideoInfo(
        val info: VideoInfo,
        val rawJson: String,
        val savedAt: Long,
    )

    private val infoCacheLock = Any()
    private val infoCache =
        object : LinkedHashMap<String, CachedVideoInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedVideoInfo>
            ): Boolean = size > INFO_CACHE_LIMIT
        }

    private fun infoCacheKey(url: String, playlistIndex: Int?): String =
        "$playlistIndex::$url"

    private fun getCachedInfo(url: String, playlistIndex: Int?): CachedVideoInfo? =
        synchronized(infoCacheLock) {
            infoCache[infoCacheKey(url, playlistIndex)]
                ?.takeIf { System.currentTimeMillis() - it.savedAt < INFO_CACHE_TTL_MS }
        }

    private fun getCachedVideoInfo(url: String, playlistIndex: Int?): VideoInfo? =
        getCachedInfo(url, playlistIndex)?.info

    private fun cacheVideoInfo(
        url: String,
        playlistIndex: Int?,
        info: VideoInfo,
        rawJson: String,
    ) {
        synchronized(infoCacheLock) {
            infoCache[infoCacheKey(url, playlistIndex)] =
                CachedVideoInfo(info, rawJson, System.currentTimeMillis())
        }
    }

    /** Materializes a fresh cached info JSON into a temp file for --load-info-json. */
    private fun getReusableInfoJsonFile(
        url: String,
        videoInfo: VideoInfo,
        taskId: String,
    ): File? {
        val cached =
            getCachedInfo(url, null)
                ?: getCachedInfo(videoInfo.originalUrl.orEmpty(), null)
                ?: return null
        return runCatching {
            val file = File(context.cacheDir, "gettube_info_$taskId.json")
            file.writeText(cached.rawJson)
            file
        }.getOrNull()
    }

    // GetTube: yt-dlp re-downloads YouTube's player JavaScript and re-solves its
    // signature challenges on every single run unless caching is enabled — the
    // engine injects --no-cache-dir into any request that lacks --cache-dir.
    // Sharing one persistent cache dir across calls keeps the player JS, the
    // solved signature functions and the challenge-solver scripts on disk, so
    // the 15-20s first info fetch drops to a few seconds on every later run.
    private fun YoutubeDLRequest.enablePersistentYtDlpCache() {
        val ytdlpCacheDir =
            File(context.cacheDir, "ytdlp-cache").apply { if (!exists()) mkdirs() }
        addOption("--cache-dir", ytdlpCacheDir.absolutePath)
    }

    // GetTube: pin JS-less YouTube player clients so a fetch never enters the
    // QuickJS signature-challenge path — benchmarked as the dominant cost of
    // every on-device extraction (10-20s+ even with a warm cache). visionos
    // is the JS-less default of current yt-dlp releases (47-49 formats incl.
    // 4K in ~2-4s); on the engine-bundled 2025.11.12 it is unsupported and
    // skipped, and android_vr serves the same role there (31 formats, full
    // 4K). yt-dlp silently drops unsupported pins, so this chain is safe
    // across engine and yt-dlp upgrades.
    private fun YoutubeDLRequest.pinFastYoutubeClients() {
        addOption("--extractor-args", "youtube:player_client=visionos,android_vr")
    }

    const val BASENAME = "%(title).200B"

    const val EXTENSION = ".%(ext)s"

    private const val ID = "[%(id)s]"

    private const val CLIP_TIMESTAMP = "%(section_start)d-%(section_end)d"

    const val OUTPUT_TEMPLATE_DEFAULT = BASENAME + EXTENSION

    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    private const val OUTPUT_TEMPLATE_CLIPS = "$BASENAME [$CLIP_TIMESTAMP]$EXTENSION"

    private const val OUTPUT_TEMPLATE_CHAPTERS =
        "chapter:$BASENAME/%(section_number)d - %(section_title).200B$EXTENSION"

    private const val OUTPUT_TEMPLATE_SPLIT = "$BASENAME/$OUTPUT_TEMPLATE_DEFAULT"

    private const val PLAYLIST_TITLE_SUBDIRECTORY_PREFIX = "%(playlist)s/"

    private const val CROP_ARTWORK_COMMAND =
        """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

    @CheckResult
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            ToastUtil.makeToastSuspend(context.getString(R.string.fetching_playlist_info))
            val request = YoutubeDLRequest(playlistURL)
            with(request) {
                //            addOption("--compat-options", "no-youtube-unavailable-videos")
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-o", BASENAME)
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
                enablePersistentYtDlpCache()
                downloadPreferences.run {
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this, toFormatSorter())
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                }
            }
            execute(request, playlistURL).out.run {
                val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") {
                    jsonFormat.decodeFromString<VideoInfo>(this)
                } else playlistInfo
            }
        }

    @CheckResult
    fun searchYouTube(query: String, resultCount: Int = 20): Result<List<PlaylistEntry>> =
        YoutubeDL.runCatching {
            val request =
                YoutubeDLRequest("ytsearch$resultCount:$query").apply {
                    addOption("--flat-playlist")
                    addOption("--dump-single-json")
                    addOption("-R", "1")
                    addOption("--socket-timeout", "5")
                }
            execute(request, SEARCH_PROCESS_ID).out.run {
                jsonFormat
                    .decodeFromString<PlaylistResult>(this)
                    .entries
                    .orEmpty()
            }
        }

    @CheckResult
    private fun getVideoInfo(
        request: YoutubeDLRequest,
        taskKey: String? = null,
    ): Result<Pair<VideoInfo, String>> =
        request.runCatching {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, taskKey, null)
            jsonFormat.decodeFromString<VideoInfo>(response.out) to response.out
        }

    @CheckResult
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<VideoInfo> {
        // Reuse a fresh info fetch (e.g. the one made by the quality sheet)
        // instead of spawning yt-dlp twice for the same video.
        getCachedVideoInfo(url, playlistIndex)?.let { return Result.success(it) }

        // GetTube build-12: native InnerTube fast path (NewPipeExtractor) —
        // the same mechanism NewPipe-class apps use: direct requests to
        // YouTube's player API, no Python process, no yt-dlp, and no QuickJS
        // signature-challenge solving at all. Measured at ~2-4s end-to-end
        // with the full quality list, versus 15-20s+ for on-device yt-dlp
        // extraction. The synthesized raw JSON is yt-dlp-compatible so the
        // --load-info-json download path works unchanged. Any failure (live
        // streams, age-gated videos, extractor breakage) falls through to the
        // pinned yt-dlp chain below.
        if (playlistIndex == null && NewPipeVideoInfoFetcher.isYouTubeVideoUrl(url)) {
            NewPipeVideoInfoFetcher.fetchVideoInfo(url)
                .getOrNull()
                ?.takeIf { !it.first.formats.isNullOrEmpty() }
                ?.let { (info, rawJson) ->
                    cacheVideoInfo(url, playlistIndex, info, rawJson)
                    return Result.success(info)
                }
        }

        with(preferences) {
            fun buildRequest(pinClients: Boolean): YoutubeDLRequest =
                YoutubeDLRequest(url).apply {
                    addOption("-o", BASENAME)
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this@with, toFormatSorter())
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    /*            if (debug) {
                        addOption("-v")
                    }*/
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    if (playlistIndex != null) {
                        addOption("--playlist-items", playlistIndex)
                        addOption("--dump-json")
                    } else {
                        addOption("--dump-single-json")
                    }
                    // GetTube: the player response alone carries every field the
                    // quality sheet needs; skipping the watch-page HTML and the tv
                    // client config shaves 1-2s of sequential round-trips per fetch.
                    if (pinClients) pinFastYoutubeClients()
                    addOption("--extractor-args", "youtube:player_skip=webpage,configs")
                    enablePersistentYtDlpCache()
                    addOption("-R", "1")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "5")
                }

            // GetTube build-11: run the fetch on JS-less YouTube clients first —
            // no player JS download and no QuickJS signature-challenge solving
            // at all, which benchmarks as the bulk of the on-device wait.
            // If the pinned run fails or returns no formats (e.g. YouTube
            // breakage), fall back to yt-dlp's default client chain — slower
            // on-device, but the battle-tested behavior everything else
            // already assumes.
            getVideoInfo(buildRequest(pinClients = true), taskKey)
                .getOrNull()
                ?.takeIf { !it.first.formats.isNullOrEmpty() }
                ?.let { (info, rawJson) ->
                    cacheVideoInfo(url, playlistIndex, info, rawJson)
                    return Result.success(info)
                }
            return getVideoInfo(buildRequest(pinClients = false), taskKey)
                .onSuccess { (info, rawJson) ->
                    cacheVideoInfo(url, playlistIndex, info, rawJson)
                }
                .map { it.first }
        }
    }

    @Serializable
    data class DownloadPreferences(
        val extractAudio: Boolean,
        val createThumbnail: Boolean,
        val downloadPlaylist: Boolean,
        val subdirectoryExtractor: Boolean,
        val subdirectoryPlaylistTitle: Boolean,
        val commandDirectory: String,
        val downloadSubtitle: Boolean,
        val embedSubtitle: Boolean,
        val keepSubtitle: Boolean,
        val subtitleLanguage: String,
        val autoSubtitle: Boolean,
        val autoTranslatedSubtitles: Boolean,
        val convertSubtitle: Int,
        val concurrentFragments: Int,
        val sponsorBlock: Boolean,
        val sponsorBlockCategory: String,
        val cookies: Boolean,
        val aria2c: Boolean,
        val useCustomAudioPreset: Boolean,
        val audioFormat: Int,
        val audioQuality: Int,
        val convertAudio: Boolean,
        val formatSorting: Boolean,
        val sortingFields: String,
        val audioConvertFormat: Int,
        val videoFormat: Int,
        val formatIdString: String,
        val videoResolution: Int,
        val privateMode: Boolean,
        val rateLimit: Boolean,
        val maxDownloadRate: String,
        val privateDirectory: Boolean,
        val cropArtwork: Boolean,
        val sdcard: Boolean,
        val sdcardUri: String,
        val embedThumbnail: Boolean,
        val videoClips: List<VideoClip>,
        val splitByChapter: Boolean,
        val debug: Boolean,
        val proxy: Boolean,
        val proxyUrl: String,
        val newTitle: String,
        val userAgentString: String,
        val outputTemplate: String,
        val useDownloadArchive: Boolean,
        val embedMetadata: Boolean,
        val restrictFilenames: Boolean,
        val supportAv1HardwareDecoding: Boolean,
        val forceIpv4: Boolean,
        val mergeAudioStream: Boolean,
        val mergeToMkv: Boolean,
    ) {
        companion object {
            val EMPTY =
                DownloadPreferences(
                    extractAudio = false,
                    createThumbnail = false,
                    downloadPlaylist = false,
                    subdirectoryExtractor = false,
                    subdirectoryPlaylistTitle = false,
                    commandDirectory = "",
                    downloadSubtitle = false,
                    embedSubtitle = false,
                    keepSubtitle = false,
                    subtitleLanguage = "",
                    autoSubtitle = false,
                    autoTranslatedSubtitles = false,
                    convertSubtitle = 0,
                    concurrentFragments = 0,
                    sponsorBlock = false,
                    sponsorBlockCategory = "",
                    cookies = false,
                    aria2c = false,
                    audioFormat = 0,
                    audioQuality = 0,
                    convertAudio = false,
                    formatSorting = false,
                    sortingFields = "",
                    audioConvertFormat = 0,
                    videoFormat = 0,
                    formatIdString = "",
                    videoResolution = 0,
                    privateMode = false,
                    rateLimit = false,
                    maxDownloadRate = "",
                    privateDirectory = false,
                    cropArtwork = false,
                    sdcard = false,
                    sdcardUri = "",
                    embedThumbnail = false,
                    videoClips = emptyList(),
                    splitByChapter = false,
                    debug = false,
                    proxy = false,
                    proxyUrl = "",
                    newTitle = "",
                    userAgentString = "",
                    outputTemplate = "",
                    useDownloadArchive = false,
                    embedMetadata = false,
                    restrictFilenames = false,
                    supportAv1HardwareDecoding = false,
                    forceIpv4 = false,
                    mergeAudioStream = false,
                    mergeToMkv = false,
                    useCustomAudioPreset = false,
                )

            fun createFromPreferences(): DownloadPreferences {
                val downloadSubtitle = SUBTITLE.getBoolean()
                val embedSubtitle = EMBED_SUBTITLE.getBoolean()
                return DownloadPreferences(
                    extractAudio = EXTRACT_AUDIO.getBoolean(),
                    createThumbnail = THUMBNAIL.getBoolean(),
                    downloadPlaylist = PLAYLIST.getBoolean(),
                    subdirectoryExtractor = SUBDIRECTORY_EXTRACTOR.getBoolean(),
                    subdirectoryPlaylistTitle = SUBDIRECTORY_PLAYLIST_TITLE.getBoolean(),
                    commandDirectory = COMMAND_DIRECTORY.getString(),
                    downloadSubtitle = downloadSubtitle,
                    embedSubtitle = embedSubtitle,
                    keepSubtitle = KEEP_SUBTITLE_FILES.getBoolean(),
                    subtitleLanguage = SUBTITLE_LANGUAGE.getString(),
                    autoSubtitle = AUTO_SUBTITLE.getBoolean(),
                    autoTranslatedSubtitles = AUTO_TRANSLATED_SUBTITLES.getBoolean(),
                    convertSubtitle = CONVERT_SUBTITLE.getInt(),
                    concurrentFragments = CONCURRENT.getInt(),
                    sponsorBlock = SPONSORBLOCK.getBoolean(),
                    sponsorBlockCategory = PreferenceUtil.getSponsorBlockCategories(),
                    cookies = COOKIES.getBoolean(),
                    aria2c = ARIA2C.getBoolean(),
                    useCustomAudioPreset = USE_CUSTOM_AUDIO_PRESET.getBoolean(),
                    audioFormat = AUDIO_FORMAT.getInt(),
                    audioQuality = AUDIO_QUALITY.getInt(),
                    convertAudio = AUDIO_CONVERT.getBoolean(),
                    formatSorting = FORMAT_SORTING.getBoolean(),
                    sortingFields = SORTING_FIELDS.getString(),
                    audioConvertFormat = PreferenceUtil.getAudioConvertFormat(),
                    videoFormat = PreferenceUtil.getVideoFormat(),
                    formatIdString = "",
                    videoResolution = PreferenceUtil.getVideoResolution(),
                    privateMode = PRIVATE_MODE.getBoolean(),
                    rateLimit = RATE_LIMIT.getBoolean(),
                    maxDownloadRate = PreferenceUtil.getMaxDownloadRate(),
                    privateDirectory = PRIVATE_DIRECTORY.getBoolean(),
                    cropArtwork = CROP_ARTWORK.getBoolean(),
                    sdcard = SDCARD_DOWNLOAD.getBoolean(),
                    sdcardUri = SDCARD_URI.getString(),
                    embedThumbnail = EMBED_THUMBNAIL.getBoolean(),
                    videoClips = emptyList(),
                    splitByChapter = false,
                    debug = DEBUG.getBoolean(),
                    proxy = PROXY.getBoolean(),
                    proxyUrl = PROXY_URL.getString(),
                    newTitle = "",
                    userAgentString =
                        USER_AGENT_STRING.run { if (USER_AGENT.getBoolean()) getString() else "" },
                    outputTemplate = OUTPUT_TEMPLATE.getString(),
                    useDownloadArchive = DOWNLOAD_ARCHIVE.getBoolean(),
                    embedMetadata = EMBED_METADATA.getBoolean(),
                    restrictFilenames = RESTRICT_FILENAMES.getBoolean(),
                    supportAv1HardwareDecoding = checkIfAv1HardwareAccelerated(),
                    forceIpv4 = FORCE_IPV4.getBoolean(),
                    mergeAudioStream = false,
                    mergeToMkv =
                        (downloadSubtitle && embedSubtitle) || MERGE_OUTPUT_MKV.getBoolean(),
                )
            }
        }
    }

    private fun YoutubeDLRequest.enableCookies(userAgentString: String): YoutubeDLRequest =
        this.addOption("--cookies", context.getCookiesFile().absolutePath).apply {
            if (userAgentString.isNotEmpty()) {
                addOption("--add-header", "User-Agent:$userAgentString")
            }
        }

    private fun YoutubeDLRequest.enableProxy(proxyUrl: String): YoutubeDLRequest =
        this.addOption("--proxy", proxyUrl)

    private fun YoutubeDLRequest.useDownloadArchive(): YoutubeDLRequest =
        this.addOption("--download-archive", context.getArchiveFile().absolutePath)

    @CheckResult
    fun getCookieListFromDatabase(): Result<List<Cookie>> = runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        SQLiteDatabase.openDatabase(
                context.dataDir.resolve("app_webview/Default/Cookies").absolutePath,
                null,
                OPEN_READONLY,
            )
            .run {
                val projection =
                    arrayOf(
                        CookieScheme.HOST,
                        CookieScheme.EXPIRY,
                        CookieScheme.PATH,
                        CookieScheme.NAME,
                        CookieScheme.VALUE,
                        CookieScheme.SECURE,
                    )
                val cookieList = mutableListOf<Cookie>()
                query("cookies", projection, null, null, null, null, null).run {
                    while (moveToNext()) {
                        val expiry = getLong(getColumnIndexOrThrow(CookieScheme.EXPIRY))
                        val name = getString(getColumnIndexOrThrow(CookieScheme.NAME))
                        val value = getString(getColumnIndexOrThrow(CookieScheme.VALUE))
                        val path = getString(getColumnIndexOrThrow(CookieScheme.PATH))
                        val secure = getLong(getColumnIndexOrThrow(CookieScheme.SECURE)) == 1L
                        val hostKey = getString(getColumnIndexOrThrow(CookieScheme.HOST))

                        val host = if (hostKey[0] != '.') ".$hostKey" else hostKey
                        cookieList.add(
                            Cookie(
                                domain = host,
                                name = name,
                                value = value,
                                path = path,
                                secure = secure,
                                expiry = expiry,
                            )
                        )
                    }
                    close()
                }
                close()
                cookieList
            }
    }

    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    private fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
        this.addOption("--downloader", "libaria2c.so")

    private fun YoutubeDLRequest.addOptionsForVideoDownloads(
        downloadPreferences: DownloadPreferences
    ): YoutubeDLRequest =
        this.apply {
            downloadPreferences.run {
                addOption("--add-metadata")
                addOption("--no-embed-info-json")
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else {
                    applyFormatSorter(this, toFormatSorter())
                }
                if (downloadSubtitle) {
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    if (embedSubtitle) {
                        addOption("--embed-subs")
                        if (keepSubtitle) {
                            addOption("--write-subs")
                        }
                    } else {
                        addOption("--write-subs")
                    }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (mergeToMkv) {
                    addOption("--remux-video", "mkv")
                    addOption("--merge-output-format", "mkv")
                }
                if (embedThumbnail) {
                    addOption("--embed-thumbnail")
                }
                if (videoClips.isEmpty()) addOption("--embed-chapters")
            }
        }

    @CheckResult
    private fun DownloadPreferences.toAudioFormatSorter(): String =
        this.run {
            if (!useCustomAudioPreset) return@run ""
            val format =
                when (audioFormat) {
                    M4A -> "acodec:aac"
                    OPUS -> "acodec:opus"
                    else -> ""
                }
            val quality =
                when (audioQuality) {
                    HIGH -> "abr~192"
                    MEDIUM -> "abr~128"
                    LOW -> "abr~64"
                    else -> ""
                }
            return@run connectWithDelimiter(format, quality, delimiter = ",")
        }

    @CheckResult
    private fun DownloadPreferences.toVideoFormatSorter(): String =
        this.run {
            val format =
                when (videoFormat) {
                    FORMAT_COMPATIBILITY -> "proto,vcodec:h264,ext"
                    FORMAT_QUALITY ->
                        if (supportAv1HardwareDecoding) {
                            "vcodec:av01"
                        } else {
                            "vcodec:vp9.2"
                        }

                    else -> ""
                }
            val res =
                when (videoResolution) {
                    1 -> "res:2160"
                    2 -> "res:1440"
                    3 -> "res:1080"
                    4 -> "res:720"
                    5 -> "res:480"
                    6 -> "res:360"
                    7 -> "+res"
                    else -> ""
                }
            val sorter = if (videoFormat == FORMAT_COMPATIBILITY) {
                connectWithDelimiter(format, res, delimiter = ",")
            } else {
                connectWithDelimiter(res, format, delimiter = ",")
            }
            return@run sorter
        }

    private fun YoutubeDLRequest.applyFormatSorter(
        preferences: DownloadPreferences,
        sorter: String,
    ) =
        preferences.run {
            if (formatSorting && sortingFields.isNotEmpty()) addOption("-S", sortingFields)
            else if (sorter.isNotEmpty()) addOption("-S", sorter) else {}
        }

    @CheckResult
    fun DownloadPreferences.toFormatSorter(): String =
        connectWithDelimiter(
            this.toVideoFormatSorter(),
            this.toAudioFormatSorter(),
            delimiter = ",",
        )

    private fun YoutubeDLRequest.addOptionsForAudioDownloads(
        id: String,
        preferences: DownloadPreferences,
        playlistUrl: String,
    ): YoutubeDLRequest =
        this.apply {
            with(preferences) {
                addOption("-x")
                if (downloadSubtitle) {
                    addOption("--write-subs")

                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else if (convertAudio) {
                    when (audioConvertFormat) {
                        CONVERT_MP3 -> {
                            addOption("--audio-format", "mp3")
                        }

                        CONVERT_M4A -> {
                            addOption("--audio-format", "m4a")
                        }
                    }
                } else {
                    applyFormatSorter(preferences, toAudioFormatSorter())
                }

                if (embedMetadata) {
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")

                    if (cropArtwork) {
                        val configFile = context.getConfigFile(id)
                        FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                        addOption("--config", configFile.absolutePath)
                    }
                }
                addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")

                if (playlistUrl.isNotEmpty()) {
                    addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
                    addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
                } else {
                    addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
                }
            }
        }

    private fun insertInfoIntoDownloadHistory(
        videoInfo: VideoInfo,
        filePaths: List<String>,
    ): List<String> =
        filePaths.onEach {
            DatabaseUtil.insertInfo(videoInfo.toDownloadedVideoInfo(videoPath = it))
        }

    private fun VideoInfo.toDownloadedVideoInfo(
        id: Int = 0,
        videoPath: String,
    ): DownloadedVideoInfo =
        this.run {
            DownloadedVideoInfo(
                id = id,
                videoTitle = title,
                videoAuthor = uploader ?: channel ?: uploaderId.toString(),
                videoUrl = webpageUrl ?: originalUrl.toString(),
                thumbnailUrl = thumbnail.toHttpsUrl(),
                videoPath = videoPath,
                extractor = extractorKey,
            )
        }

    private fun insertSplitChapterIntoHistory(videoInfo: VideoInfo, filePaths: List<String>) =
        filePaths.onEach {
            DatabaseUtil.insertInfo(
                videoInfo.toDownloadedVideoInfo(videoPath = it).copy(videoTitle = it.getFileName())
            )
        }

    @CheckResult
    fun downloadVideo(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        if (videoInfo == null)
            return Result.failure(Throwable(context.getString(R.string.fetch_info_error_msg)))

        with(downloadPreferences) {
            val url =
                playlistUrl.ifEmpty {
                    videoInfo.originalUrl
                        ?: videoInfo.webpageUrl
                        ?: return Result.failure(
                            Throwable(context.getString(R.string.fetch_info_error_msg))
                        )
                }
            val request = YoutubeDLRequest(url)
            val pathBuilder = StringBuilder()
            val outputBuilder = StringBuilder()

            // GetTube: hand the JSON the sheet already fetched back to yt-dlp;
            // with --load-info-json it skips its whole extraction phase, so the
            // download starts on the bytes right away instead of sitting at 0%
            // while the video is re-analyzed. yt-dlp ignores the positional URL
            // in this mode and falls back to re-extraction if the JSON is stale.
            val cachedInfoJson =
                if (playlistItem == 0 && videoClips.isEmpty()) {
                    getReusableInfoJsonFile(url, videoInfo, taskId)
                } else null

            request
                .apply {
                    addOption("--no-mtime")
                    // Same caching rationale as the quality-sheet fetch: keep the
                    // player JS and signature functions on disk for the extraction
                    // runs that do happen here (playlists, retries, stale JSON).
                    enablePersistentYtDlpCache()
                    if (cachedInfoJson != null) {
                        addOption("--load-info-json", cachedInfoJson.absolutePath)
                    } else {
                        // No reusable JSON: this run extracts on its own, so skip
                        // the same round-trips the sheet fetch skips as well, and
                        // pin the same JS-less clients to stay out of the QuickJS
                        // signature-challenge path (playlists re-extract per entry).
                        pinFastYoutubeClients()
                        addOption("--extractor-args", "youtube:player_skip=webpage,configs")
                    }
                    //                addOption("-v")
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (debug) {
                        addOption("-v")
                    }
                    if (useDownloadArchive) {
                        val archiveFile = context.getArchiveFile()
                        val archiveFileContent = archiveFile.readText()
                        if (archiveFileContent.contains("${videoInfo.extractor} ${videoInfo.id}")) {
                            return Result.failure(
                                YoutubeDLException(
                                    context.getString(R.string.download_archive_error)
                                )
                            )
                        } else {
                            useDownloadArchive()
                        }
                    }

                    if (rateLimit && maxDownloadRate.isNumberInRange(1, 1000000)) {
                        addOption("-r", "${maxDownloadRate}K")
                    }

                    if (playlistItem != 0 && downloadPlaylist) {
                        addOption("--playlist-items", playlistItem)
                        if (subdirectoryPlaylistTitle && !videoInfo.playlist.isNullOrEmpty()) {
                            outputBuilder.append(PLAYLIST_TITLE_SUBDIRECTORY_PREFIX)
                        }
                        //                    addOption("--compat-options",
                        // "no-youtube-unavailable-videos")
                    } else {
                        addOption("--no-playlist")
                    }

                    if (aria2c) {
                        enableAria2c()
                    } else if (concurrentFragments > 1) {
                        addOption("--concurrent-fragments", concurrentFragments)
                    }

                    if (extractAudio || (videoInfo.vcodec == "none")) {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(audioDownloadDir)
                        addOptionsForAudioDownloads(
                            id = videoInfo.id,
                            preferences = downloadPreferences,
                            playlistUrl = playlistUrl,
                        )
                    } else {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(videoDownloadDir)
                        addOptionsForVideoDownloads(downloadPreferences)
                    }
                    if (sponsorBlock) {
                        addOption("--sponsorblock-remove", sponsorBlockCategory)
                    }

                    if (createThumbnail) {
                        addOption("--write-thumbnail")
                        addOption("--convert-thumbnails", "png")
                    }
                    if (subdirectoryExtractor) {
                        pathBuilder.append("/${videoInfo.extractorKey}")
                    }

                    if (sdcard) {
                        addOption("-P", context.getSdcardTempDir(videoInfo.id).absolutePath)
                    } else {
                        addOption("-P", pathBuilder.toString())
                    }

                    videoClips.forEach {
                        addOption(
                            "--download-sections",
                            "*%d-%d".format(locale = Locale.US, it.start, it.end),
                        )
                    }
                    if (newTitle.isNotEmpty()) {
                        addCommands(listOf("--replace-in-metadata", "title", ".+", newTitle))
                    }
                    if (Build.VERSION.SDK_INT > 23 && !sdcard)
                        addOption("-P", "temp:" + getExternalTempDir())

                    if (splitByChapter) {
                        addOption("-o", OUTPUT_TEMPLATE_CHAPTERS)
                        addOption("--split-chapters")
                    }

                    val output =
                        if (splitByChapter) {
                            OUTPUT_TEMPLATE_SPLIT
                        } else if (videoClips.isEmpty()) {
                            outputTemplate
                        } else {
                            OUTPUT_TEMPLATE_CLIPS
                        }

                    addOption("-o", outputBuilder.append(output).toString())

                    for (s in request.buildCommand()) Log.d(TAG, s)
                }
                .runCatching {
                    YoutubeDL.getInstance()
                        .execute(request = this, processId = taskId, callback = progressCallback)
                        .also { cachedInfoJson?.delete() }
                }
                .onFailure { th ->
                    cachedInfoJson?.delete()
                    return if (
                        sponsorBlock &&
                            th.message?.contains("Unable to communicate with SponsorBlock API") ==
                                true
                    ) {
                        th.printStackTrace()
                        onFinishDownloading(
                            preferences = this,
                            videoInfo = videoInfo,
                            downloadPath = pathBuilder.toString(),
                            sdcardUri = sdcardUri,
                        )
                    } else Result.failure(th)
                }
            return onFinishDownloading(
                preferences = this,
                videoInfo = videoInfo,
                downloadPath = pathBuilder.toString(),
                sdcardUri = sdcardUri,
            )
        }
    }

    private fun onFinishDownloading(
        preferences: DownloadPreferences,
        videoInfo: VideoInfo,
        downloadPath: String,
        sdcardUri: String,
    ): Result<List<String>> =
        preferences.run {
            val fileName =
                preferences.newTitle.ifEmpty {
                    videoInfo.filename
                        ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
                        ?: videoInfo.title
                }

            Log.d(TAG, "onFinishDownloading: $fileName")
            if (sdcard) {
                moveFilesToSdcard(
                        sdcardUri = sdcardUri,
                        tempPath = context.getSdcardTempDir(videoInfo.id),
                    )
                    .onSuccess {
                        if (privateMode) {
                            return Result.success(emptyList())
                        } else if (splitByChapter) {
                            insertSplitChapterIntoHistory(videoInfo, it)
                        } else {
                            insertInfoIntoDownloadHistory(videoInfo, it)
                        }
                    }
            } else {
                FileUtil.scanFileToMediaLibraryPostDownload(
                        title = fileName,
                        downloadDir = downloadPath,
                    )
                    .run {
                        if (privateMode) Result.success(emptyList())
                        else
                            Result.success(
                                if (splitByChapter) {
                                    insertSplitChapterIntoHistory(videoInfo, this)
                                } else {
                                    insertInfoIntoDownloadHistory(videoInfo, this)
                                }
                            )
                    }
            }
        }

    @CheckResult
    fun executeCustomCommandTask(
        urlString: String,
        taskId: String,
        template: CommandTemplate,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit),
    ): Result<YoutubeDLResponse> {
        val urlList = urlString.split(Regex("[\n ]")).filter { it.isNotBlank() }

        val request =
            with(preferences) {
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                }
            }

        return runCatching {
            YoutubeDL.getInstance()
                .execute(request = request, processId = taskId, callback = progressCallback)
        }
    }

    suspend fun executeCommandInBackground(
        url: String,
        template: CommandTemplate = PreferenceUtil.getTemplate(),
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ) {
        downloadPreferences.run {
            val taskId = Downloader.makeKey(url = url, templateName = template.name)
            val notificationId = taskId.toNotificationId()
            val urlList = url.split(Regex("[\n ]")).filter { it.isNotBlank() }

            ToastUtil.makeToastSuspend(context.getString(R.string.start_execute))
            val request =
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                }

            onProcessStarted()
            withContext(Dispatchers.Main) { onTaskStarted(template, url) }
            runCatching {
                    val response =
                        YoutubeDL.getInstance().execute(request = request, processId = taskId) {
                            progress,
                            _,
                            text ->
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = taskId,
                                progress = progress.toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                            Downloader.updateTaskOutput(
                                template = template,
                                url = url,
                                line = text,
                                progress = progress,
                            )
                        }
                    onTaskEnded(template, url, response.out + "\n" + response.err)
                }
                .onFailure {
                    it.printStackTrace()
                    if (it is YoutubeDL.CanceledException) return@onFailure
                    it.message.run {
                        if (isNullOrEmpty()) onTaskEnded(template, url)
                        else onTaskError(this, template, url)
                    }
                }
            onProcessEnded()
        }
    }

    private fun checkIfAv1HardwareAccelerated(): Boolean {
        if (PreferenceUtil.containsKey(AV1_HARDWARE_ACCELERATED)) {
            return AV1_HARDWARE_ACCELERATED.getBoolean()
        } else {
            val res =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    false
                } else {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
                            info.isHardwareAccelerated
                    }
                }
            AV1_HARDWARE_ACCELERATED.updateBoolean(res)
            return res
        }
    }
}
