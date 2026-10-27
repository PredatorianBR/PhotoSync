package com.davic.whatssync

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class WhatsappAccessibilityService : AccessibilityService() {

    private val tag = "AccessibilityService"
    private var targetContactName: String? = null
    
    // Automation states
    private enum class AutoState {
        IDLE,
        OPENING_PROFILE,
        EXPANDING_PHOTO,
        CAPTURING_PHOTO,
        FINISHING
    }
    
    private var currentState = AutoState.IDLE
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "WhatsSync Accessibility Service Connected")
        SyncManager.getInstance(applicationContext).setAccessibilityService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "WhatsSync Accessibility Service Destroyed")
        SyncManager.getInstance(applicationContext).setAccessibilityService(null)
    }

    override fun onInterrupt() {
        Log.w(tag, "Service interrupted")
        currentState = AutoState.IDLE
    }

    fun setTargetContact(name: String) {
        targetContactName = name
        currentState = AutoState.OPENING_PROFILE
        Log.d(tag, "Target contact set to: $name, state = OPENING_PROFILE")
    }

    fun forceBack() {
        handler.post {
            performGlobalAction(GLOBAL_ACTION_BACK)
            currentState = AutoState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (currentState == AutoState.IDLE) return

        val rootNode = rootInActiveWindow ?: return

        when (currentState) {
            AutoState.OPENING_PROFILE -> {
                // 1. Check if we are already in the "Dados do contato" / "Contact info" screen.
                if (isContactInfoScreen(rootNode)) {
                    Log.d(tag, "Detected Contact Info screen. Expanding photo...")
                    currentState = AutoState.EXPANDING_PHOTO
                    expandProfilePhoto(rootNode)
                } 
                // 2. Or, if we are in a Chat screen, we can click the Chat Header containing the contact's name
                else if (isChatScreen(rootNode)) {
                    Log.d(tag, "Detected Chat screen. Clicking chat header...")
                    clickChatHeader(rootNode)
                }
            }
            AutoState.EXPANDING_PHOTO -> {
                if (isFullScreenPhotoScreen(rootNode)) {
                    Log.d(tag, "Detected Full Screen Photo screen. Setting capture delay...")
                    currentState = AutoState.CAPTURING_PHOTO
                    handler.postDelayed({
                        captureScreenPhoto()
                    }, 1500) // Wait for photo to load in high resolution
                } else {
                    // Try expanding again if it didn't work
                    expandProfilePhoto(rootNode)
                }
            }
            AutoState.CAPTURING_PHOTO -> {
                // Waiting for screenshot callback to complete
            }
            AutoState.FINISHING -> {
                // Cleanup / idle
            }
            else -> {}
        }
    }

    private fun isContactInfoScreen(root: AccessibilityNodeInfo): Boolean {
        // WhatsApp contact info screen displays contact status, phone number list, etc.
        val search1 = findNodeByText(root, "Mídia")
        val search2 = findNodeByText(root, "Media")
        val search3 = findNodeByText(root, "Silenciar")
        val search4 = findNodeByText(root, "Mute")
        val search5 = findNodeByText(root, "Notificações")
        val search6 = findNodeByText(root, "Notifications")
        val search7 = findNodeByText(root, "Mensagens temporárias")
        val search8 = findNodeByText(root, "Disappearing messages")
        return search1 != null || search2 != null || search3 != null || search4 != null ||
               search5 != null || search6 != null || search7 != null || search8 != null
    }

    private fun isChatScreen(root: AccessibilityNodeInfo): Boolean {
        // Chat screen has the message input field or call icons
        val search1 = findNodeByText(root, "Mensagem")
        val search2 = findNodeByText(root, "Message")
        val search3 = findNodeByDescription(root, "Chamada de voz")
        val search4 = findNodeByDescription(root, "Voice call")
        val search5 = findNodeByDescription(root, "Mais opções")
        val search6 = findNodeByDescription(root, "More options")
        val search7 = findNodeByDescription(root, "Anexar")
        val search8 = findNodeByDescription(root, "Attach")
        return search1 != null || search2 != null || search3 != null || search4 != null ||
               search5 != null || search6 != null || search7 != null || search8 != null
    }

    private fun isFullScreenPhotoScreen(root: AccessibilityNodeInfo): Boolean {
        // Full screen profile picture shows the image and options like Share/Compartilhar
        val search1 = findNodeByDescription(root, "Compartilhar")
        val search2 = findNodeByDescription(root, "Share")
        val search3 = findNodeByDescription(root, "Editar")
        val search4 = findNodeByDescription(root, "Edit")
        val search5 = findNodeByDescription(root, "Navegar para cima")
        val search6 = findNodeByDescription(root, "Navigate up")
        
        val hasShareOrEdit = search1 != null || search2 != null || search3 != null || search4 != null
        val isNotInfo = !isContactInfoScreen(root)
        val isNotChat = !isChatScreen(root)
        
        return (hasShareOrEdit || (search5 != null || search6 != null)) && isNotInfo && isNotChat
    }

    private fun clickChatHeader(root: AccessibilityNodeInfo) {
        val contactName = targetContactName
        
        if (contactName != null) {
            val nameNode = findNodeByText(root, contactName)
            if (nameNode != null && clickNode(nameNode)) {
                Log.d(tag, "Clicked chat header name node")
                return
            }
        }

        // Fallback: search for top toolbar/action bar container and click it
        val titleArea = findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            id.contains("conversation_contact_name") || id.contains("toolbar") || id.contains("action_bar")
        }
        if (titleArea != null && clickNode(titleArea)) {
            Log.d(tag, "Clicked chat toolbar/title area via viewId")
            return
        }
        
        Log.d(tag, "Contact name node not found. Trying fallback header click.")
    }

    private fun expandProfilePhoto(root: AccessibilityNodeInfo) {
        // Look for the profile picture ImageView or Avatar
        val avatarNode = findNodeByCriteria(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            desc.contains("foto") || desc.contains("profile") || desc.contains("picture") || 
            desc.contains("avatar") || desc.contains("imagem do perfil") ||
            id.contains("picture") || id.contains("avatar") || id.contains("photo")
        }

        if (avatarNode != null) {
            val clicked = clickNode(avatarNode)
            Log.d(tag, "Clicked profile photo: $clicked")
        } else {
            Log.w(tag, "Profile photo node not found in contact info screen")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenPhoto() {
        Log.d(tag, "Taking screenshot...")
        
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            applicationContext.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    
                    // Hardware bitmaps cannot be cropped/modified directly, copy to software config
                    val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    
                    hardwareBuffer.close()
                    bitmap?.recycle()

                    if (softwareBitmap != null) {
                        Log.d(tag, "Screenshot captured successfully")
                        SyncManager.getInstance(applicationContext).notifyPhotoCaptured(softwareBitmap)
                    } else {
                        Log.e(tag, "Failed to copy hardware bitmap to software config")
                    }

                    // Go back twice to return to our app
                    returnToApp()
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(tag, "Failed to take screenshot: $errorCode")
                    returnToApp()
                }
            }
        )
    }

    private fun returnToApp() {
        currentState = AutoState.FINISHING
        // Back from Full Screen Photo to Contact Info
        performGlobalAction(GLOBAL_ACTION_BACK)
        
        handler.postDelayed({
            // Back from Contact Info to WhatsSync
            performGlobalAction(GLOBAL_ACTION_BACK)
            currentState = AutoState.IDLE
            Log.d(tag, "Returned to WhatsSync, state reset to IDLE")
        }, 1000)
    }

    // Helper functions for node traversing
    
    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        return findNodeByCriteria(node) {
            it.text?.toString()?.contains(text, ignoreCase = true) == true
        }
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        return findNodeByCriteria(node) {
            it.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
        }
    }

    private fun findNodeByCriteria(
        node: AccessibilityNodeInfo?,
        criteria: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (criteria(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findNodeByCriteria(child, criteria)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return clickNode(node.parent)
    }
}
