package com.diegonmarcos.superapp.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process log ring-buffer. WebView console + Kotlin log lines flow here;
 *  the Config tab reads it via collectAsState(). */
internal object LogStore {
    private const val MAX = 500
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(line: String) {
        val cur = _lines.value
        _lines.value = if (cur.size >= MAX) cur.drop(1) + line else cur + line
    }

    fun clear() { _lines.value = emptyList() }
}
