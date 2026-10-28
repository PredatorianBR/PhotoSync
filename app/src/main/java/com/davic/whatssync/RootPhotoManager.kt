package com.davic.whatssync

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootPhotoManager {

    private const val TAG = "RootPhotoManager"

    // WhatsApp packages to scan
    private val WHATSAPP_PACKAGES = listOf(
        "com.whatsapp.w4b",
        "com.whatsapp"
    )

    /**
     * Checks if root access (su) is available to this app process.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            output != null && (output.contains("uid=0") || output.contains("root"))
        } catch (e: Exception) {
            Log.d(TAG, "Root not available: ${e.message}")
            false
        }
    }

    /**
     * Extracts phone JID -> LID mappings from WhatsApp's msgstore.db database.
     * E.g. "5511987315739@s.whatsapp.net" -> "101924036722719"
     */
    fun getPhoneToLidMapping(): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        for (pkg in WHATSAPP_PACKAGES) {
            val query = "SELECT j2.raw_string, j1.raw_string FROM jid_map JOIN jid j1 ON jid_map.lid_row_id = j1._id JOIN jid j2 ON jid_map.jid_row_id = j2._id;"
            val cmd = arrayOf("su", "-c", "sqlite3 /data/data/$pkg/databases/msgstore.db \"$query\" 2>/dev/null")
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line?.split("|")
                    if (parts != null && parts.size >= 2) {
                        val phoneJid = parts[0].trim() // e.g. 5511987315739@s.whatsapp.net
                        val lidJid = parts[1].trim()   // e.g. 101924036722719@lid
                        val lidNum = lidJid.substringBefore("@")
                        mapping[phoneJid] = lidNum
                    }
                }
                process.waitFor()
                if (mapping.isNotEmpty()) {
                    Log.d(TAG, "Extracted ${mapping.size} phone->LID mappings from $pkg")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying jid_map from $pkg", e)
            }
        }
        return mapping
    }

    /**
     * Lists all cached full-resolution photos (typically 640x640) in WhatsApp's cache.
     * Returns a set of filenames without path, e.g. "101924036722719.jpg".
     */
    fun getHighResCachedPhotos(): Set<String> {
        val files = mutableSetOf<String>()
        for (pkg in WHATSAPP_PACKAGES) {
            val cmd = arrayOf("su", "-c", "ls /data/data/$pkg/cache/Profile\\ Pictures/ 2>/dev/null")
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: ""
                    if (trimmed.endsWith(".jpg") || trimmed.endsWith(".png")) {
                        files.add(trimmed)
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Error listing high-res cache from $pkg", e)
            }
        }
        return files
    }

    /**
     * Lists all standard avatar thumbnails in WhatsApp's files/Avatars/ directory.
     * Returns a set of filenames, e.g. "5511987315739@s.whatsapp.net.j".
     */
    fun getAvatarThumbnails(): Set<String> {
        val files = mutableSetOf<String>()
        for (pkg in WHATSAPP_PACKAGES) {
            val cmd = arrayOf("su", "-c", "ls /data/data/$pkg/files/Avatars/ 2>/dev/null")
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: ""
                    if (trimmed.endsWith("@s.whatsapp.net.j")) {
                        files.add(trimmed)
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Error listing avatars from $pkg", e)
            }
        }
        return files
    }

    /**
     * Reads photo bytes from a WhatsApp internal path using su.
     * Copies file to our app's cache directory and reads it directly.
     */
    fun readPhotoBytes(internalPath: String, cacheDir: File): ByteArray? {
        val tempFile = File(cacheDir, "temp_profile_sync.jpg")
        return try {
            if (tempFile.exists()) tempFile.delete()

            // Escape spaces for shell
            val escapedPath = internalPath.replace(" ", "\\ ")
            val cmd = arrayOf(
                "su", "-c",
                "cp $escapedPath ${tempFile.absolutePath} && chmod 666 ${tempFile.absolutePath}"
            )
            val process = Runtime.getRuntime().exec(cmd)
            process.waitFor()

            if (tempFile.exists() && tempFile.length() > 0) {
                val bytes = tempFile.readBytes()
                tempFile.delete()
                bytes
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading photo bytes from $internalPath", e)
            null
        }
    }

    /**
     * Finds the best photo for a contact given their phone number or JID.
     * Prioritizes:
     * 1. 640x640 high-resolution cache matching LID
     * 2. 640x640 high-resolution cache matching phone number
     * 3. Thumbnail avatar in files/Avatars/
     *
     * Returns Pair<ByteArray, Boolean> where Boolean indicates isHighRes (true = 640x640).
     */
    fun findPhotoForContact(
        cleanPhone: String,
        phoneToLid: Map<String, String>,
        highResCache: Set<String>,
        avatarThumbs: Set<String>,
        cacheDir: File
    ): Pair<ByteArray, Boolean>? {
        val jid = if (cleanPhone.contains("@")) cleanPhone else "$cleanPhone@s.whatsapp.net"
        val phoneDigits = cleanPhone.substringBefore("@")
        val lid = phoneToLid[jid]

        for (pkg in WHATSAPP_PACKAGES) {
            // Check 1: High-res by LID (e.g. 101924036722719.jpg)
            if (lid != null && highResCache.contains("$lid.jpg")) {
                val path = "/data/data/$pkg/cache/Profile Pictures/$lid.jpg"
                val bytes = readPhotoBytes(path, cacheDir)
                if (bytes != null) return Pair(bytes, true)
            }

            // Check 2: High-res by phone number directly (e.g. 5511987315739.jpg)
            if (highResCache.contains("$phoneDigits.jpg")) {
                val path = "/data/data/$pkg/cache/Profile Pictures/$phoneDigits.jpg"
                val bytes = readPhotoBytes(path, cacheDir)
                if (bytes != null) return Pair(bytes, true)
            }

            // Check 3: Avatar thumbnail (e.g. 5511987315739@s.whatsapp.net.j)
            val thumbName = "$jid.j"
            if (avatarThumbs.contains(thumbName)) {
                val path = "/data/data/$pkg/files/Avatars/$thumbName"
                val bytes = readPhotoBytes(path, cacheDir)
                if (bytes != null) return Pair(bytes, false)
            }
        }

        return null
    }
}
