/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.genericwebdav

import com.diegonmarcos.mediacenter.cloud.core.CloudServerInfo
import com.diegonmarcos.mediacenter.cloud.core.ProviderType
import com.diegonmarcos.mediacenter.cloud.webdav.WebDavDialect
import com.diegonmarcos.mediacenter.cloud.webdav.WebDavSession

/**
 * Plain RFC-4918 WebDAV dialect — the lowest common denominator. No previews,
 * favorites, quota or sharing; thumbnails fall back to client-side downscaling
 * of the original file. Works with any standards-compliant WebDAV server
 * (Apache/nginx mod_dav, Yandex Disk, pCloud, kDrive, Koofr, mailbox.org, …).
 *
 * The configured server URL is treated as the root of the user's collection,
 * so [filesEndpoint] is empty and PROPFIND hrefs are resolved against it.
 */
class GenericWebDavDialect : WebDavDialect() {
    override val providerType = ProviderType.WEBDAV
    override val displayName = "WebDAV"

    override suspend fun serverInfo(session: WebDavSession): CloudServerInfo =
        CloudServerInfo(version = "WebDAV", serverName = "WebDAV")
}
