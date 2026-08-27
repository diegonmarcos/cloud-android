/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.standalone

import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point for [android.provider.MediaStore.ACTION_REVIEW_SECURE] launches
 * (e.g. reviewing a freshly captured photo from a secure camera session).
 *
 * This is a thin subclass of [StandaloneActivity] that exists purely so the
 * manifest can declare android:showWhenLocked / android:turnScreenOn for the
 * secure flow only. Declaring those attributes in the manifest (rather than
 * relying solely on the runtime setShowWhenLocked call) is what reliably
 * occludes a secure keyguard on the first frame, so the review opens on top of
 * the lock screen instead of prompting the user to unlock first.
 */
@AndroidEntryPoint
class SecureStandaloneActivity : StandaloneActivity()
