/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.core

/**
 * Process-wide snapshot of the user's cloud viewer/advanced preferences.
 *
 * These preferences are stored per-account on [CloudServerConfig], but the settings screens
 * only ever edit the single active account, so a lightweight global snapshot (populated from
 * the active config on startup and whenever the settings change) is enough for the pipeline
 * to consult cheaply from any thread — without threading a `configId` through every
 * image/log call site.
 *
 * Only [verboseLogging] is consumed today (via [CloudTrace]); the remaining fields are kept
 * here so future consumption is a one-line read rather than another plumbing pass.
 */
object CloudRuntimeSettings {

    @Volatile
    var verboseLogging: Boolean = false

    @Volatile
    var preferRemoteImages: Boolean = false

    @Volatile
    var readOnlyMode: Boolean = false

    @Volatile
    var loadPreviewImage: Boolean = true

    @Volatile
    var loadOriginalImage: Boolean = false

    @Volatile
    var autoPlayVideos: Boolean = true

    @Volatile
    var loopVideos: Boolean = false

    @Volatile
    var forceOriginalVideo: Boolean = false

    /** Applies the given config's preferences to the global snapshot. Null resets to defaults. */
    fun apply(config: CloudServerConfig?) {
        verboseLogging = config?.verboseLogging ?: false
        preferRemoteImages = config?.preferRemoteImages ?: false
        readOnlyMode = config?.readOnlyMode ?: false
        loadPreviewImage = config?.loadPreviewImage ?: true
        loadOriginalImage = config?.loadOriginalImage ?: false
        autoPlayVideos = config?.autoPlayVideos ?: true
        loopVideos = config?.loopVideos ?: false
        forceOriginalVideo = config?.forceOriginalVideo ?: false
    }
}
