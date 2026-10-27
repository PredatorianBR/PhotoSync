package com.davic.whatssync

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

enum class SyncStatus {
    PENDING,
    SYNCING,
    SUCCESS,
    FAILED
}

data class PhoneContact(
    val contactId: Long,
    val name: String,
    val phoneNumber: String,
    val photoUri: String?,
    val hasPhoto: Boolean,
    val whatsappDataId: Long?, // ID in ContactsContract.Data for WhatsApp profile link
    val whatsappRawContactId: Long?,
    var syncStatus: SyncStatus = SyncStatus.PENDING,
    var errorMessage: String? = null
)

class ContactManager(private val context: Context) {

    private val tag = "ContactManager"

    /**
     * Query all contacts on the device and attempt to link them with their WhatsApp info.
     */
    fun fetchContacts(): List<PhoneContact> {
        val contactList = mutableListOf<PhoneContact>()
        val resolver = context.contentResolver

        // 1. Query WhatsApp data rows to map contact JIDs and IDs
        // MIME types for WhatsApp raw contacts
        val whatsappMimes = arrayOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
        )
        val whatsappMap = mutableMapOf<Long, Pair<Long, Long>>() // ContactId -> Pair(DataId, RawContactId)

        val dataCursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.RAW_CONTACT_ID
            ),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            whatsappMimes,
            null
        )

        dataCursor?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Data._ID)
            val contactIdIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val rawContactIdIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)

            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(idIndex)
                val contactId = cursor.getLong(contactIdIndex)
                val rawContactId = cursor.getLong(rawContactIdIndex)
                whatsappMap[contactId] = Pair(dataId, rawContactId)
            }
        }

        // 2. Query all phone contacts
        val phoneCursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.PHOTO_ID
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        val processedContacts = mutableSetOf<Long>()

        phoneCursor?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoUriIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            val photoIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_ID)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(contactIdIndex)
                
                // Avoid listing duplicates if a contact has multiple phone numbers
                if (processedContacts.contains(contactId)) {
                    continue
                }

                val name = cursor.getString(nameIndex) ?: "Desconhecido"
                val number = cursor.getString(numberIndex) ?: ""
                val photoUri = cursor.getString(photoUriIndex)
                val photoId = cursor.getLong(photoIdIndex)
                val hasPhoto = photoId > 0

                val whatsappInfo = whatsappMap[contactId]
                val whatsappDataId = whatsappInfo?.first
                val whatsappRawContactId = whatsappInfo?.second

                // We want to show contacts that can potentially be synced (we link those that have WhatsApp raw contacts)
                contactList.add(
                    PhoneContact(
                        contactId = contactId,
                        name = name,
                        phoneNumber = number,
                        photoUri = photoUri,
                        hasPhoto = hasPhoto,
                        whatsappDataId = whatsappDataId,
                        whatsappRawContactId = whatsappRawContactId
                    )
                )
                processedContacts.add(contactId)
            }
        }

        Log.d(tag, "Fetched ${contactList.size} contacts, linked ${whatsappMap.size} with WhatsApp")
        return contactList
    }

    /**
     * Finds the writeable Raw Contact IDs (e.g., Google contacts, local device contacts) associated with a Contact ID.
     * We don't write photos to the read-only WhatsApp Raw Contacts.
     */
    private fun getWriteableRawContactIds(contactId: Long): List<Long> {
        val rawContactIds = mutableListOf<Long>()
        val resolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.RawContacts._ID)
            val typeIndex = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)

            while (it.moveToNext()) {
                val rawId = it.getLong(idIndex)
                val accountType = it.getString(typeIndex)
                
                // Ignore WhatsApp account types as they are read-only
                if (accountType != "com.whatsapp" && accountType != "com.whatsapp.w4b") {
                    rawContactIds.add(rawId)
                }
            }
        }

        return rawContactIds
    }

    /**
     * Updates the photo of a given Contact ID by writing to its writeable Raw Contacts.
     */
    fun updateContactPhoto(contactId: Long, photoBytes: ByteArray): Boolean {
        val writeableRawIds = getWriteableRawContactIds(contactId)
        if (writeableRawIds.isEmpty()) {
            Log.w(tag, "No writeable raw contacts found for contactId $contactId")
            return false
        }

        var success = false
        val resolver = context.contentResolver

        for (rawContactId in writeableRawIds) {
            try {
                // Check if photo row already exists for this raw contact
                val photoCursor = resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
                    null
                )

                var photoRowId: Long? = null
                photoCursor?.use {
                    if (it.moveToFirst()) {
                        photoRowId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Data._ID))
                    }
                }

                val ops = ArrayList<ContentProviderOperation>()

                if (photoRowId != null) {
                    // Update existing photo row
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data._ID} = ?",
                                arrayOf(photoRowId.toString())
                            )
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            .build()
                    )
                    Log.d(tag, "Updating photo row $photoRowId for rawContactId $rawContactId")
                } else {
                    // Insert new photo row
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            .build()
                    )
                    Log.d(tag, "Inserting new photo row for rawContactId $rawContactId")
                }

                val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                if (results.isNotEmpty()) {
                    success = true
                }
            } catch (e: Exception) {
                Log.e(tag, "Error saving photo to rawContactId $rawContactId", e)
            }
        }

        return success
    }
}
