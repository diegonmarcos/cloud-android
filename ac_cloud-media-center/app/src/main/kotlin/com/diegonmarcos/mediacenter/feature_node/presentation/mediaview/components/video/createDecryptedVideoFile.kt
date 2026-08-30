package com.diegonmarcos.mediacenter.feature_node.presentation.mediaview.components.video

import androidx.core.net.toFile
import com.diegonmarcos.mediacenter.feature_node.data.data_source.KeychainHolder
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.util.getUri
import java.io.File
import java.io.FileOutputStream

fun <T: Media> createDecryptedVideoFile(keychainHolder: KeychainHolder, decryptedMedia: T): File {
    val tempFile = File.createTempFile("${decryptedMedia.id}.temp", null)
    val encryptedFile = decryptedMedia.getUri().toFile()
    val decrypted = keychainHolder.decryptVaultMedia(encryptedFile)
    // Stream decrypted content to temp file (constant memory for VLTv2 via tempFile backing)
    decrypted.openStream().use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }
    decrypted.cleanup()
    return tempFile
}