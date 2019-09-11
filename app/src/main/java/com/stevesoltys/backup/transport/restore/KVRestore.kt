package com.stevesoltys.backup.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.backup.crypto.Crypto
import com.stevesoltys.backup.decodeBase64
import com.stevesoltys.backup.header.HeaderReader
import com.stevesoltys.backup.header.UnsupportedVersionException
import libcore.io.IoUtils.closeQuietly
import java.io.IOException
import java.util.*
import javax.crypto.AEADBadTagException

private class KVRestoreState(
        internal val token: Long,
        internal val packageInfo: PackageInfo)

private val TAG = KVRestore::class.java.simpleName

internal class KVRestore(
        private val plugin: KVRestorePlugin,
        private val outputFactory: OutputFactory,
        private val headerReader: HeaderReader,
        private val crypto: Crypto) {

    private var state: KVRestoreState? = null

    /**
     * Return true if there are records stored for the given package.
     */
    @Throws(IOException::class)
    fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return plugin.hasDataForPackage(token, packageInfo)
    }

    /**
     * This prepares to restore the given package from the given restore token.
     *
     * It is possible that the system decides to not restore the package.
     * Then a new state will be initialized right away without calling other methods.
     */
    fun initializeState(token: Long, packageInfo: PackageInfo) {
        state = KVRestoreState(token, packageInfo)
    }

    /**
     * Get the data for the current package.
     *
     * @param data An open, writable file into which the key/value backup data should be stored.
     * @return One of [TRANSPORT_OK]
     * or [TRANSPORT_ERROR] (an error occurred, the restore should be aborted and rescheduled).
     */
    fun getRestoreData(data: ParcelFileDescriptor): Int {
        val state = this.state ?: throw IllegalStateException()

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory.
        // We return the data in lexical order sorted by key,
        // so that apps which use synthetic keys like BLOB_1, BLOB_2, etc
        // will see the date in the most obvious order.
        val sortedKeys = getSortedKeys(state.token, state.packageInfo)
        if (sortedKeys == null) {
            // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "No keys for package: ${state.packageInfo.packageName}")
            return TRANSPORT_ERROR
        }

        // We expect at least some data if the directory exists in the first place
        Log.v(TAG, "  getRestoreData() found ${sortedKeys.size} key files")

        return try {
            val dataOutput = outputFactory.getBackupDataOutput(data)
            for (keyEntry in sortedKeys) {
                readAndWriteValue(state, keyEntry, dataOutput)
            }
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Unable to read backup records", e)
            TRANSPORT_ERROR
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while reading backup records", e)
            TRANSPORT_ERROR
        } catch (e: UnsupportedVersionException) {
            Log.e(TAG, "Unsupported version in backup: ${e.version}", e)
            TRANSPORT_ERROR
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "Decryption failed", e)
            TRANSPORT_ERROR
        } finally {
            this.state = null
            closeQuietly(data)
        }
    }

    /**
     * Return a list of the records (represented by key files) in the given directory,
     * sorted lexically by the Base64-decoded key file name, not by the on-disk filename.
     */
    private fun getSortedKeys(token: Long, packageInfo: PackageInfo): List<DecodedKey>? {
        val records: List<String> = try {
            plugin.listRecords(token, packageInfo)
        } catch (e: IOException) {
            return null
        }
        if (records.isEmpty()) return null

        // Decode the key filenames into keys then sort lexically by key
        val contents = ArrayList<DecodedKey>()
        for (recordKey in records) contents.add(DecodedKey(recordKey))
        contents.sort()
        return contents
    }

    /**
     * Read the encrypted value for the given key and write it to the given [BackupDataOutput].
     */
    @Throws(IOException::class, UnsupportedVersionException::class, SecurityException::class)
    private fun readAndWriteValue(state: KVRestoreState, dKey: DecodedKey, out: BackupDataOutput) {
        val inputStream = plugin.getInputStreamForRecord(state.token, state.packageInfo, dKey.base64Key)
        try {
            val version = headerReader.readVersion(inputStream)
            crypto.decryptHeader(inputStream, version, state.packageInfo.packageName, dKey.key)
            val value = crypto.decryptSegment(inputStream)
            val size = value.size
            Log.v(TAG, "    ... key=${dKey.key} size=$size")

            out.writeEntityHeader(dKey.key, size)
            out.writeEntityData(value, size)
        } finally {
            closeQuietly(inputStream)
        }
    }

    private class DecodedKey(internal val base64Key: String) : Comparable<DecodedKey> {
        internal val key = base64Key.decodeBase64()

        override fun compareTo(other: DecodedKey) = key.compareTo(other.key)
    }

}