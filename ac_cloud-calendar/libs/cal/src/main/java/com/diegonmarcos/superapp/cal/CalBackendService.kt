package com.diegonmarcos.superapp.cal

import com.diegonmarcos.superapp.core.DataBackendService

/**
 * [CalEngine] behind core's IDataBackend, shipped in Cloud-Lib-Cal.apk.
 *
 * The CalDAV config arrives as an argument on the methods that need it - the
 * app owns the credentials, this process never stores them.
 */
class CalBackendService : DataBackendService() {

    private val engine by lazy { CalEngine(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(
        "calendars", "events", "sync", "projects", "todos",
        "saveTodo", "setTodoStatus", "deleteTodo", "syncTodos", "testCaldav",
        "seed", "hasData",
    )

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        "calendars" -> engine.calendars()
        "events"    -> engine.events(
            args.getOrNull(0)?.toLongOrNull() ?: 0L,
            args.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE,
        )
        "sync"      -> engine.sync()
        "projects"  -> engine.projects()
        "todos"     -> engine.todos(args.getOrNull(0).orEmpty())
        "saveTodo"  -> engine.saveTodo(args.getOrNull(0).orEmpty(), args.getOrNull(1))
        "setTodoStatus" -> engine.setTodoStatus(
            args.getOrNull(0).orEmpty(),
            args.getOrNull(1)?.toBoolean() ?: false,
            args.getOrNull(2),
        )
        "deleteTodo" -> engine.deleteTodo(args.getOrNull(0).orEmpty(), args.getOrNull(1))
        "syncTodos"  -> engine.syncTodos(args.getOrNull(0))
        "testCaldav" -> engine.testCaldav(args.getOrNull(0))
        // Cutover handoff for tasks a re-sync cannot rebuild - see CalEngine.seed.
        "seed"       -> engine.seed(args.getOrNull(0).orEmpty())
        "hasData"    -> engine.hasData()
        else -> throw IllegalArgumentException("unknown method: $method")
    }
}
