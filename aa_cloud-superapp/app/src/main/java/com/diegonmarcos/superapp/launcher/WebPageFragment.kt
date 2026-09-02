package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment

/**
 * A section page that IS a web page — declare `url` on the page in
 * build.json and it renders here instead of needing a Kotlin screen.
 *
 * Cloud ▸ Linktree is the reason it exists: the linktree is already a
 * maintained site, and porting it to tiles meant keeping two copies of the
 * same link list in step forever. One field in build.json is the whole
 * integration; [SectionPages.factoryFor] picks this up for ANY page that
 * declares a url, so the next embedded page needs no code either.
 *
 * Deliberately chrome-less: no address bar, no tabs. This is a page of the
 * app that happens to be rendered from the web, not a browser — a real
 * browsing session is what ac_cloud-browser (extapp:cloud-browser) is for.
 */
class WebPageFragment : Fragment() {

    private var web: WebView? = null

    /** Disabled until the WebView actually has history, so a back press on
     *  the first page pops the fragment like every other page instead of
     *  being swallowed here. */
    private val backToPreviousPage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            web?.takeIf { it.canGoBack() }?.goBack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val url = arguments?.getString(ARG_URL).orEmpty()
        val wv = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Keep navigation INSIDE the page. Without a WebViewClient the
            // platform hands every link to an ACTION_VIEW chooser, so the
            // first tap leaves the app — which is the one thing an embedded
            // page must not do.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    backToPreviousPage.isEnabled = view?.canGoBack() == true
                }
            }
            if (url.isNotBlank()) loadUrl(url)
        }
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, backToPreviousPage)
        web = wv
        return wv
    }

    override fun onDestroyView() {
        // A WebView outliving its fragment leaks the whole view hierarchy.
        web?.let { it.stopLoading(); it.destroy() }
        web = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String) = WebPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
