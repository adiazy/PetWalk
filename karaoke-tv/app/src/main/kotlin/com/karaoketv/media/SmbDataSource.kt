package com.karaoketv.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.min
import com.hierynomus.smbj.share.File as SmbFile

/** Streams `smb://` URIs from the network share with random access, so seeking works. */
@UnstableApi
class SmbDataSource(private val connection: () -> SmbConnection?) : BaseDataSource(true) {
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val smb = connection() ?: throw IOException("No network music folder is configured")
        val path = smb.pathOf(dataSpec.uri) ?: throw IOException("${dataSpec.uri} is not on ${smb.location}")
        val f = try {
            smb.openRead(path)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        file = f
        val size = f.fileInformation.standardInformation.endOfFile
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("Not opened")
        val read = try {
            f.read(buffer, position, offset, min(length.toLong(), bytesRemaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { file?.close() }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Sends `smb://` to [SmbDataSource] and everything else (files, content://, http) to the default. */
@UnstableApi
class KaraokeDataSourceFactory(
    context: Context,
    private val connection: () -> SmbConnection?,
) : DataSource.Factory {
    private val default = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource =
        RoutingDataSource(default.createDataSource(), SmbDataSource(connection))
}

@UnstableApi
private class RoutingDataSource(
    private val fallback: DataSource,
    private val smb: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "smb") smb else fallback
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
