package com.streamflixreborn.streamflix.utils

import android.app.AlertDialog
import android.graphics.Color
import android.widget.TextView

/**
 * Consistent button colors for every AlertDialog in the app.
 *
 * Dialog buttons normally inherit the theme's accent color, which resolves to
 * black on several dark themes and becomes unreadable. This forces the brand
 * red on the primary (accept) action and white on dismiss actions so
 * confirmations are always visible no matter which theme is active.
 */
object DialogTheme {
    /** Brand red used for the primary/accept button. */
    @androidx.annotation.ColorInt
    const val ACCEPT_COLOR = 0xFFE50914.toInt()

    /** White used for cancel/neutral/dismiss buttons. */
    @androidx.annotation.ColorInt
    const val DISMISS_COLOR = Color.WHITE

    /** Colors the buttons of a platform [android.app.AlertDialog]. Call before show(). */
    fun style(dialog: AlertDialog) {
        styleWithButtons(dialog, dialog::getButton)
    }

    /** Colors the buttons of an [androidx.appcompat.app.AlertDialog]. Call before show(). */
    fun style(dialog: androidx.appcompat.app.AlertDialog) {
        styleWithButtons(dialog, dialog::getButton)
    }

    private fun styleWithButtons(
        dialog: android.app.Dialog,
        getButton: (Int) -> TextView?,
    ) {
        dialog.setOnShowListener {
            getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ACCEPT_COLOR)
            getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(DISMISS_COLOR)
            getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(DISMISS_COLOR)
        }
    }
}
