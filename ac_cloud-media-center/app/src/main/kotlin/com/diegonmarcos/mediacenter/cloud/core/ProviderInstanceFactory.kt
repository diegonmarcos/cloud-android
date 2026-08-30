/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core

/**
 * Mints fresh, fully isolated provider instances — one per configured cloud account.
 *
 * Each provider source set contributes a single factory (via Hilt `@IntoSet`). The
 * [com.diegonmarcos.mediacenter.cloud.di.CloudProviderInitializer] calls [create] once per active
 * [com.diegonmarcos.mediacenter.cloud.data.entity.CloudServerConfigEntity] and registers the result in
 * [ProviderRegistry] under that config's id. This is what allows several accounts of the
 * **same** provider type (e.g. two Immich servers) to coexist: every account gets its own
 * provider object with its own base URL, auth state and HTTP client, instead of sharing one
 * mutable singleton.
 */
interface ProviderInstanceFactory {
    val providerType: ProviderType

    /** Builds a brand-new, unconfigured provider instance. */
    fun create(): MediaCapabilityProvider
}
