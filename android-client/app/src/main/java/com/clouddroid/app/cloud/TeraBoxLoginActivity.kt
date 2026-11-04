package com.clouddroid.app.cloud

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URI

/**
 * Signs in on the real TeraBox website inside a WebView (same approach as the cloudmemory app).
 * CloudDroid never sees the password: it only reads the session cookie the website sets once you are signed in,
 * and hands that cookie back to the caller, which sends it to your CloudDroid backend over HTTPS.
 *
 * Unofficial: TeraBox has no public API for personal storage, and automated use may be against its terms.
 */
class TeraBoxLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var done: Button
    private lateinit var hint: TextView
    private lateinit var site: String
    private val cookies: CookieManager = CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sign in to TeraBox"
        site = intent.getStringExtra(EXTRA_SITE)?.takeIf { it.isNotBlank() } ?: DEFAULT_SITE

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        hint = TextView(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 14f
            setTextColor(Color.parseColor("#9AA4B2"))
            text = "This is TeraBox's own website; pinch to zoom. Sign in with your TeraBox e-mail and password " +
                "(Google sign-in is usually refused inside an app). When your files appear, press the button."
        }
        page.addView(hint)

        web = WebView(this)
        page.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        done = Button(this).apply {
            text = "I'm signed in"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { finishWithCookie() }
        }
        page.addView(done)
        setContentView(page)

        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        // Start clean so an old sign-in is never mistaken for the account being added.
        cookies.removeAllCookies { web.loadUrl("$site/main") }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The desktop website, zoomed out and zoomable.
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = refreshButton()
        }
    }

    /** Cookies from the website host and from the "dm." API host (the sign-in can be stored under either). */
    private fun currentCookie(): String {
        val merged = LinkedHashMap<String, String>()
        val host = URI.create(site).host
        val urls = mutableListOf(site)
        if (host != null) {
            val root = host.removePrefix("www.")
            if (!host.startsWith("dm.")) urls += "https://dm.$root"
            urls += "https://$root"
        }
        for (u in urls) {
            val c = cookies.getCookie(u) ?: continue
            for (part in c.split(";")) {
                val p = part.trim()
                val eq = p.indexOf('=')
                if (eq > 0) merged.putIfAbsent(p.substring(0, eq), p)
            }
        }
        return merged.values.joinToString("; ")
    }

    private fun refreshButton() {
        val signedIn = currentCookie().contains("ndus=")
        done.isEnabled = signedIn
        if (signedIn) hint.text = "TeraBox accepted your sign-in. Press \"I'm signed in\" to continue."
    }

    private fun finishWithCookie() {
        val c = currentCookie()
        if (!c.contains("ndus=")) {
            refreshButton()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIE, c).putExtra(EXTRA_SITE, site))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_COOKIE = "cookie"
        const val DEFAULT_SITE = "https://www.1024terabox.com"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
