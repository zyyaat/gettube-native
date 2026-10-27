package com.junkfood.seal.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil.toCookiesFileContent
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateLong

/**
 * GetTube build-20: SnapTube-style zero-setup session warming.
 *
 * YouTube's "Sign in to confirm you're not a bot" enforcement is IP-level,
 * and it hits totally cookie-less API requests hardest. Any real browser
 * visit establishes an ANONYMOUS visitor session (VISITOR_INFO1_LIVE, YSC,
 * SOCS — no account involved, nothing to log into), and yt-dlp requests
 * carrying those cookies are treated as returning visitors instead of
 * anonymous bots.
 *
 * A headless, never-attached WebView quietly loads youtube.com once every
 * 24h, lets YouTube issue its anonymous cookies, flushes them into the
 * WebView cookie database, then exports cookies.txt and auto-enables the
 * COOKIES preference. No login, no settings, no UI — the user sees nothing.
 */
object AnonymousCookieWarmup {

    private const val LAST_WARMUP_AT = "gettube_anon_cookie_warmup_at"
    private const val THROTTLE_MS = 24 * 60 * 60 * 1000L
    private const val WARMUP_URL = "https://www.youtube.com/"

    // Grace period after onPageFinished: YouTube's HTML response headers set
    // the core visitor cookies immediately, but some (PREF/SOCS refinements)
    // land via JS a moment later. flush() then persists asynchronously to the
    // cookie database, hence the second settle delay before the DB export.
    private const val GRACE_MS = 6000L
    private const val FLUSH_SETTLE_MS = 2500L

    /** Fire-and-forget, safe to call from any thread. Throttled to 1/24h. */
    fun warmUpIfNeeded() {
        runCatching {
            val now = System.currentTimeMillis()
            if (now - LAST_WARMUP_AT.getLong(0L) < THROTTLE_MS) return
            LAST_WARMUP_AT.updateLong(now)
            val appContext: Context = App.context
            Handler(Looper.getMainLooper()).post { startWarmup(appContext) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWarmup(appContext: Context) {
        var webView: WebView? = null
        try {
            val main = Handler(Looper.getMainLooper())
            webView =
                WebView(MutableContextWrapper(appContext)).apply {
                    // Never attached to any window: manual layout lets the
                    // page render offscreen and YouTube's JS run, exactly
                    // like a background tab. Invisible + image-blocked.
                    visibility = View.INVISIBLE
                    settings.run {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = true
                    }
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                main.postDelayed(
                                    {
                                        runCatching {
                                            CookieManager.getInstance().flush()
                                            main.postDelayed(
                                                { exportAndEnable() },
                                                FLUSH_SETTLE_MS,
                                            )
                                            view.stopLoading()
                                            view.destroy()
                                        }
                                    },
                                    GRACE_MS,
                                )
                            }
                        }
                }
            CookieManager.getInstance().run {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.layout(0, 0, 1080, 1920)
            webView.loadUrl(WARMUP_URL)
        } catch (th: Throwable) {
            runCatching { webView?.destroy() }
        }
    }

    private fun exportAndEnable() {
        runCatching {
            val cookies = DownloadUtil.getCookieListFromDatabase().getOrNull().orEmpty()
            if (cookies.isEmpty()) return
            FileUtil.writeContentToFile(
                cookies.toCookiesFileContent(),
                App.context.getCookiesFile(),
            )
            // Fresh anonymous cookies are worthless while the COOKIES switch
            // stays off — this whole feature exists to be invisible, so flip
            // it automatically. No account is involved, ever.
            if (!COOKIES.getBoolean()) COOKIES.updateBoolean(true)
        }
    }
}
