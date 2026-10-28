package com.davic.whatssync

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val contactName: String, val index: Int, val total: Int, val details: String = "") : SyncState()
    data class Completed(val successCount: Int, val failedCount: Int) : SyncState()
    object Paused : SyncState()
}

class SyncManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val contactManager = ContactManager(appContext)
    private val tag = "SyncManager"

    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _contacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val contacts: StateFlow<List<PhoneContact>> = _contacts

    private var queue = mutableListOf<PhoneContact>()
    private var currentIndex = 0
    private var successCount = 0
    private var failedCount = 0

    // Callback used by the Accessibility Service to deliver captured photos
    private var onPhotoCapturedCallback: ((Bitmap) -> Unit)? = null

    // Weak reference to the running Accessibility Service instance
    private var serviceRef: WeakReference<WhatsappAccessibilityService>? = null

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable

    // Root cache maps
    private var phoneToLidMap: Map<String, String> = emptyMap()
    private var highResCachedPhotos: Set<String> = emptySet()
    private var avatarThumbnails: Set<String> = emptySet()

    companion object {
        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context).also { instance = it }
            }
        }
    }

    init {
        checkRootAccess()
    }

    fun checkRootAccess() {
        scope.launch(Dispatchers.IO) {
            val hasRoot = RootPhotoManager.isRootAvailable()
            _isRootAvailable.value = hasRoot
            Log.d(tag, "Root access available: $hasRoot")
        }
    }

    fun setAccessibilityService(service: WhatsappAccessibilityService?) {
        serviceRef = if (service != null) WeakReference(service) else null
        _isAccessibilityConnected.value = service != null
        Log.d(tag, "Accessibility Service reference updated: ${service != null}")
    }

    fun isAccessibilityServiceConnected(): Boolean {
        // Double check: either running instance is registered or enabled in system settings
        val isBound = serviceRef?.get() != null
        if (isBound) return true

        return isAccessibilityServiceEnabledInSettings()
    }

    fun isAccessibilityServiceEnabledInSettings(): Boolean {
        val expectedServiceName = "${appContext.packageName}/${WhatsappAccessibilityService::class.java.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                componentName.contains(appContext.packageName)) {
                return true
            }
        }
        return false
    }

    fun loadContacts() {
        scope.launch {
            checkRootAccess()
            val fetched = withContext(Dispatchers.IO) {
                contactManager.fetchContacts()
            }
            _contacts.value = fetched
        }
    }

    /**
     * Start the sync process for selected contacts.
     */
    fun startSync(selectedContacts: List<PhoneContact>) {
        if (syncJob?.isActive == true) {
            Log.w(tag, "Sync is already running")
            return
        }

        queue = selectedContacts.map { it.copy(syncStatus = SyncStatus.PENDING) }.toMutableList()
        if (queue.isEmpty()) {
            _syncState.value = SyncState.Completed(0, 0)
            return
        }

        currentIndex = 0
        successCount = 0
        failedCount = 0
        
        // Update contact list statuses
        updateContactsStatuses(queue)

        syncJob = scope.launch {
            // If root is available, pre-load WhatsApp database and caches in background
            if (_isRootAvailable.value) {
                withContext(Dispatchers.IO) {
                    phoneToLidMap = RootPhotoManager.getPhoneToLidMapping()
                    highResCachedPhotos = RootPhotoManager.getHighResCachedPhotos()
                    avatarThumbnails = RootPhotoManager.getAvatarThumbnails()
                }
            }
            runSyncLoop()
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        _syncState.value = SyncState.Idle
        queue.clear()
        loadContacts()
    }

    private suspend fun runSyncLoop() {
        val hasRoot = _isRootAvailable.value

        while (currentIndex < queue.size) {
            val contact = queue[currentIndex]
            _syncState.value = SyncState.Syncing(contact.name, currentIndex + 1, queue.size)
            
            // Mark contact as syncing
            updateContactStatus(contact.contactId, SyncStatus.SYNCING)

            var success = false

            // METHOD 1: Direct High-Resolution / Avatar Extraction via Root
            if (hasRoot) {
                success = withContext(Dispatchers.IO) {
                    syncContactDirectlyViaRoot(contact)
                }
            }

            // METHOD 2: Fallback to Accessibility Screenshot if root is not available or photo not in cache
            if (!success && isAccessibilityServiceConnected()) {
                success = syncSingleContactViaAccessibility(contact)
            }

            if (success) {
                successCount++
                updateContactStatus(contact.contactId, SyncStatus.SUCCESS)
            } else {
                failedCount++
                updateContactStatus(contact.contactId, SyncStatus.FAILED)
            }

            currentIndex++
            // If using root direct extraction, delay can be very minimal (~100ms)
            // If using accessibility UI automation, delay is 2-3s
            if (hasRoot) {
                delay(100)
            } else {
                delay(2000)
            }
        }

        _syncState.value = SyncState.Completed(successCount, failedCount)
    }

    /**
     * Direct root extraction: Reads 640x640 high-resolution cache or avatar directly from WhatsApp databases.
     */
    private fun syncContactDirectlyViaRoot(contact: PhoneContact): Boolean {
        try {
            // Determine phone number or JID
            val cleanPhone = if (!contact.whatsappJid.isNullOrBlank()) {
                contact.whatsappJid
            } else {
                contact.phoneNumber.replace(Regex("[^0-9]"), "")
            }

            if (cleanPhone.isBlank()) {
                Log.w(tag, "No phone number or JID for contact: ${contact.name}")
                return false
            }

            val result = RootPhotoManager.findPhotoForContact(
                cleanPhone = cleanPhone,
                phoneToLid = phoneToLidMap,
                highResCache = highResCachedPhotos,
                avatarThumbs = avatarThumbnails,
                cacheDir = appContext.cacheDir
            )

            if (result != null) {
                val (photoBytes, isHighRes) = result
                val updated = contactManager.updateContactPhoto(contact.contactId, photoBytes)
                if (updated) {
                    val resType = if (isHighRes) "Alta Resolução 640x640" else "Miniatura"
                    Log.d(tag, "✓ [Root] $resType aplicada para ${contact.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error syncing contact via root for ${contact.name}", e)
        }
        return false
    }

    private suspend fun syncSingleContactViaAccessibility(contact: PhoneContact): Boolean {
        Log.d(tag, "Starting accessibility sync for contact: ${contact.name}")

        var photoCapturedBitmap: Bitmap? = null
        val lock = Object()
        
        onPhotoCapturedCallback = { bitmap ->
            synchronized(lock) {
                photoCapturedBitmap = bitmap
                lock.notify()
            }
        }

        // 1. Open contact in WhatsApp
        val launched = openContactInWhatsapp(contact)
        if (!launched) {
            Log.e(tag, "Failed to launch WhatsApp for contact ${contact.name}")
            return false
        }

        // Notify accessibility service of the current target contact details
        serviceRef?.get()?.setTargetContact(contact.name)

        // 2. Wait for photo to be captured by the service (timeout after 12 seconds)
        val timeoutMs = 12000L
        val startTime = System.currentTimeMillis()
        
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                while (photoCapturedBitmap == null && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    try {
                        lock.wait(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }

        onPhotoCapturedCallback = null
        val bitmap = photoCapturedBitmap

        if (bitmap == null) {
            Log.w(tag, "Sync timed out for ${contact.name} (No photo captured)")
            withContext(Dispatchers.Main) {
                serviceRef?.get()?.forceBack()
            }
            return false
        }

        // 3. Process image and update contact photo
        return withContext(Dispatchers.IO) {
            try {
                val photoBytes = processBitmap(bitmap)
                contactManager.updateContactPhoto(contact.contactId, photoBytes)
            } catch (e: Exception) {
                Log.e(tag, "Error processing/saving photo for ${contact.name}", e)
                false
            }
        }
    }

    /**
     * Launch WhatsApp for the contact.
     */
    private fun openContactInWhatsapp(contact: PhoneContact): Boolean {
        return try {
            if (contact.whatsappDataId != null) {
                val mimeType = contact.whatsappMimeType ?: "vnd.android.cursor.item/vnd.com.whatsapp.profile"
                val dataUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contact.whatsappDataId)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(dataUri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                Log.d(tag, "Opened WhatsApp Profile for ${contact.name} via raw contact link ($mimeType)")
                true
            } else {
                val cleanNumber = contact.phoneNumber.replace(Regex("[^0-9]"), "")
                val uriString = "https://api.whatsapp.com/send?phone=$cleanNumber"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                Log.d(tag, "Opened WhatsApp Chat for ${contact.name} via API fallback: $uriString")
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error launching WhatsApp intent", e)
            false
        }
    }

    /**
     * Crop the captured screen photo to the square avatar and compress to JPEG format.
     */
    private fun processBitmap(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val minSize = Math.min(width, height)
        
        val x = (width - minSize) / 2
        val y = (height - minSize) / 2

        val cropped = Bitmap.createBitmap(bitmap, x, y, minSize, minSize)
        val scaled = Bitmap.createScaledBitmap(cropped, 640, 640, true)
        
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        
        if (cropped != bitmap) cropped.recycle()
        scaled.recycle()
        bitmap.recycle()

        return stream.toByteArray()
    }

    /**
     * Accessibility service calls this to deliver the photo screenshot.
     */
    fun notifyPhotoCaptured(bitmap: Bitmap) {
        Log.d(tag, "notifyPhotoCaptured called with bitmap")
        onPhotoCapturedCallback?.invoke(bitmap)
    }

    private fun updateContactsStatuses(syncQueue: List<PhoneContact>) {
        val currentList = _contacts.value.toMutableList()
        val queueIds = syncQueue.map { it.contactId }.toSet()
        for (i in currentList.indices) {
            if (queueIds.contains(currentList[i].contactId)) {
                currentList[i] = currentList[i].copy(syncStatus = SyncStatus.PENDING)
            }
        }
        _contacts.value = currentList
    }

    private fun updateContactStatus(contactId: Long, status: SyncStatus) {
        val currentList = _contacts.value.toMutableList()
        val index = currentList.indexOfFirst { it.contactId == contactId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(syncStatus = status)
            _contacts.value = currentList
        }
    }
}
