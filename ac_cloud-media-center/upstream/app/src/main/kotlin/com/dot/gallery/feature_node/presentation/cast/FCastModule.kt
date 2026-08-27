/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.cast

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * FCastSession is provided as a @Singleton by constructor injection.
 * This module exists to ensure the cast package is included in Hilt's component graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object FCastModule
