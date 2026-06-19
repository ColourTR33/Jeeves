package com.jeeves.desktop.hotkey

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Global hotkey manager using JNativeHook.
 * Listens for Ctrl+Shift+R to toggle recording.
 */
class HotkeyManager : NativeKeyListener {

    var onToggleRecording: (() -> Unit)? = null

    private var ctrlPressed = false
    private var shiftPressed = false

    init {
        // Suppress JNativeHook logging
        val logger = Logger.getLogger(GlobalScreen::class.java.`package`.name)
        logger.level = Level.OFF
        logger.useParentHandlers = false

        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
        } catch (e: NativeHookException) {
            System.err.println("Failed to register native hook: ${e.message}")
        }
    }

    override fun nativeKeyPressed(event: NativeKeyEvent) {
        when (event.keyCode) {
            NativeKeyEvent.VC_CONTROL -> ctrlPressed = true
            NativeKeyEvent.VC_SHIFT -> shiftPressed = true
            NativeKeyEvent.VC_R -> {
                if (ctrlPressed && shiftPressed) {
                    onToggleRecording?.invoke()
                }
            }
        }
    }

    override fun nativeKeyReleased(event: NativeKeyEvent) {
        when (event.keyCode) {
            NativeKeyEvent.VC_CONTROL -> ctrlPressed = false
            NativeKeyEvent.VC_SHIFT -> shiftPressed = false
        }
    }

    override fun nativeKeyTyped(event: NativeKeyEvent) {
        // Not used
    }

    fun shutdown() {
        try {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        } catch (e: Exception) {
            // Ignore shutdown errors
        }
    }
}
