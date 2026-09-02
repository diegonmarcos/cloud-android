package com.diegonmarcos.cloudme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import org.json.JSONObject

/**
 * A page that IS the web page.
 *
 * Profile > Professional and Profile > Personal are the LinkedIn and Instagram
 * layouts from front-diegonmarcos/b-Media/mySocials, rendered by loading those
 * exact files out of assets. They were reimplemented with this app's blocks
 * first, and that was wrong: a reimplementation is a second design to keep in
 * step, and it was visibly not the same page. The HTML and CSS already exist
 * and are already the design — so ship them.
 *
 * data/regen-web.py copies each page plus everything it references, so the
 * bundle is what the browser would have fetched. Images and fonts still come
 * from the CDN the pages already use, which is why this needs the network the
 * app already declares.
 */
class WebFragment : Fragment() {

    private var web: WebView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val url = arguments?.getString(ARG_URL).orEmpty()
        val view = WebView(inflater.context).apply {
            settings.javaScriptEnabled = true          // the pages hydrate from PORTAL_DATA
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Same-origin file access, so a page in assets can pull its own
            // stylesheets and data scripts. Nothing outside assets is reachable.
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            webViewClient = WebViewClient()
            setBackgroundColor(0xFF0B0414.toInt())
        }
        web = view
        if (s != null) view.restoreState(s) else view.loadUrl("file:///android_asset/$url")

        // Back walks the page's own history before it leaves the section — the
        // profiles have internal navigation and losing it would strand you.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (view.canGoBack()) view.goBack() else { isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed() }
                }
            }.also { back ->
                view.viewTreeObserver.addOnGlobalLayoutListener { back.isEnabled = view.canGoBack() }
            })
        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web?.apply { stopLoading(); destroy() }
        web = null
    }

    companion object {
        private const val ARG_URL = "url"

        /** `{"kind":"fragment","id":"web","url":"mysocials/linkedin.html"}` */
        fun newInstance(block: JSONObject): WebFragment = WebFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, block.optString("url")) }
        }
    }
}
