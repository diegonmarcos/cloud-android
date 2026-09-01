package com.diegonmarcos.mediacenter.injection

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.module.AppGlideModule
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedFileModelLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedGenericImageDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedGifDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedMediaSource
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedMediaStream
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedSourceToStreamLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedStreamingFileLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedStreamingUriLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedUriModelLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.EncryptedVideoFrameDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.HeifEncryptedDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.HeifEncryptedSourceDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.HeifMimeInputStreamDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.JxlBitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.SandboxedHeifBitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.SandboxedHeifMimeDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.SandboxedJxlBitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.JxlEncryptedDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.JxlEncryptedSourceDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.MimeInputStream
import com.diegonmarcos.mediacenter.cloud.image.CloudGlideModelLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.MimeInputStreamModelLoader
import com.diegonmarcos.mediacenter.core.decoder.glide.PsdBitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.Jp2BitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.SvgBitmapDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.TiffMimeInputStreamDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.RawMimeInputStreamDecoder
import com.diegonmarcos.mediacenter.core.decoder.glide.StreamingEncryptedVideoFrameDecoder
import java.io.File
import java.io.InputStream

@GlideModule
class GlideModule: AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val pool: BitmapPool = glide.bitmapPool

        // Cloud media URI loader (cloud://{provider}/{remoteId})
        registry.prepend(
            Uri::class.java,
            InputStream::class.java,
            CloudGlideModelLoader.Factory()
        )

        // New streaming model loaders (File/Uri -> EncryptedMediaSource -> InputStream) placed first.
        registry.prepend(
            File::class.java,
            EncryptedMediaSource::class.java,
            EncryptedStreamingFileLoader.Factory(context)
        )
        registry.prepend(
            Uri::class.java,
            EncryptedMediaSource::class.java,
            EncryptedStreamingUriLoader.Factory(context)
        )
        registry.prepend(
            EncryptedMediaSource::class.java,
            java.io.InputStream::class.java,
            EncryptedSourceToStreamLoader.Factory()
        )

        // Legacy byte-array path (will still catch cases needing format-specific decoders).
        // ModelLoaders: intercept both File and Uri BEFORE defaults.
        registry.prepend(
            File::class.java,
            EncryptedMediaStream::class.java,
            EncryptedFileModelLoader.Factory(context)
        )
        registry.prepend(
            Uri::class.java,
            EncryptedMediaStream::class.java,
            EncryptedUriModelLoader.Factory(context)
        )

        registry.prepend(
            Uri::class.java,
            MimeInputStream::class.java,
            MimeInputStreamModelLoader.Factory(context)
        )
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            HeifMimeInputStreamDecoder(context, pool)
        )
        // TIFF via content Uri MIME (image/tiff is reliably reported by MediaStore)
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            TiffMimeInputStreamDecoder(pool)
        )
        // Camera RAW (CR2/NEF/ARW/DNG/ORF/PEF/RW2/SRW/…) via content Uri MIME. Android can't
        // decode these natively, so the grid renders their embedded JPEG preview instead.
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            RawMimeInputStreamDecoder(pool)
        )
        // Formats Android can't decode natively, detected by magic bytes (MIME is unreliable):
        // PSD, JPEG 2000, and SVG (rasterized). Registered on the InputStream path.
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            PsdBitmapDecoder(pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            Jp2BitmapDecoder(pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            SvgBitmapDecoder(pool)
        )
        // Sandboxed MIME decoder: when enabled, intercepts HEIF before HeifMimeInputStreamDecoder
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            SandboxedHeifMimeDecoder(context, pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            JxlBitmapDecoder(pool)
        )
        // Sandboxed decoders: when enabled, intercept before the standard decoders above
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            SandboxedHeifBitmapDecoder(context, pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            SandboxedJxlBitmapDecoder(context, pool)
        )

        // Decoders for our custom model type
        registry.prepend(
            EncryptedMediaStream::class.java,
            Bitmap::class.java,
            HeifEncryptedDecoder(pool)
        )

        // Bridging decoders: EncryptedMediaSource -> Bitmap (HEIF/JXL) without forcing legacy byte array for all images.
        registry.prepend(
            EncryptedMediaSource::class.java,
            Bitmap::class.java,
            HeifEncryptedSourceDecoder(pool)
        )
        registry.prepend(
            EncryptedMediaSource::class.java,
            Bitmap::class.java,
            JxlEncryptedSourceDecoder(pool)
        )
        // Streaming video frame decoder (EncryptedMediaSource -> Bitmap) preferred over legacy byte-array path
        registry.prepend(
            EncryptedMediaSource::class.java,
            Bitmap::class.java,
            StreamingEncryptedVideoFrameDecoder(pool, context.applicationContext) { context.cacheDir }
        )
        registry.prepend(
            EncryptedMediaStream::class.java,
            Bitmap::class.java,
            JxlEncryptedDecoder(pool)
        )
        registry.prepend(
            EncryptedMediaStream::class.java,
            Bitmap::class.java,
            EncryptedVideoFrameDecoder(pool) { context.cacheDir }
        )
        // GIF decoder for encrypted media - must be registered before generic image decoder
        // to properly handle animated GIFs in vault
        registry.prepend(
            EncryptedMediaStream::class.java,
            GifDrawable::class.java,
            EncryptedGifDecoder(context, pool, glide.arrayPool)
        )
        registry.prepend(
            EncryptedMediaStream::class.java,
            Bitmap::class.java,
            EncryptedGenericImageDecoder(pool)
        )
    }

    // Disable manifest parsing for speed
    override fun isManifestParsingEnabled(): Boolean = false

}