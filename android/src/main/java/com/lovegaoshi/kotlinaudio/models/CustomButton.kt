package com.lovegaoshi.kotlinaudio.models

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

@UnstableApi
data class CustomButton (
    val displayName: String = "",
    val iconRes: Int = 0,
    val sessionCommand: String? = null,
    val onLayout: Boolean = false,
    // Slots the button may take (CommandButton.SLOT_*). Empty lets media3 place
    // it; SLOT_OVERFLOW keeps it out of the compact notification while surfaces
    // that render every action — Android Auto — still show it.
    val slots: IntArray = intArrayOf(),
    val commandButton: CommandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName(displayName)
        .setIconResId(iconRes)
        .setSessionCommand(SessionCommand(sessionCommand ?: displayName, Bundle()))
        .apply { if (slots.isNotEmpty()) setSlots(*slots) }
        .build()
)
