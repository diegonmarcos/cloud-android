package com.diegonmarcos.superapp.news

import com.diegonmarcos.superapp.core.DataBackendService

/** [NewsEngine] behind core's IDataBackend, shipped in Cloud-Lib-News.apk. */
class NewsBackendService : DataBackendService() {

    private val engine by lazy { NewsEngine(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(
        "topics", "articles", "timeline", "sync",
        "saved", "toggleSaved", "events", "mediaChannels", "seed", "hasData",
    )

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        "topics"        -> engine.topics()
        "articles"      -> engine.articles(args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty())
        "timeline"      -> engine.timeline(args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty())
        "sync"          -> engine.sync(args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty())
        "saved"         -> engine.saved()
        "toggleSaved"   -> engine.toggleSaved(args.getOrNull(0).orEmpty())
        "events"        -> engine.events(
            args.getOrNull(0)?.toLongOrNull() ?: 0L,
            args.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE,
        )
        "mediaChannels" -> engine.mediaChannels()
        // Cutover handoff for saved articles - see NewsEngine.seed.
        "seed"          -> engine.seed(args.getOrNull(0).orEmpty())
        "hasData"       -> engine.hasData()
        else -> throw IllegalArgumentException("unknown method: $method")
    }
}
