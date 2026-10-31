package com.dpsmpx.dpclock.platform

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpclock.data.deviceStorage

/**
 * «Не беспокоить» на работу помидора (D-025, п. 1): включается, только если было
 * выключено, и выключается, только если его не меняли, — режим человека не трогается.
 * Режим — приоритетный, с исключениями из настроек телефона; будильники в нём звучат.
 */
object FocusMode {
    private const val PREFS = "focus"
    private const val KEY_OWNED = "owned"

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun apply(context: Context, wanted: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return
        val prefs = context.deviceStorage().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val owned = prefs.getBoolean(KEY_OWNED, false)
        val filter = manager.currentInterruptionFilter
        when {
            wanted && !owned && filter == NotificationManager.INTERRUPTION_FILTER_ALL -> {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                prefs.edit { putBoolean(KEY_OWNED, true) }
            }
            !wanted && owned -> {
                // Человек сам сменил режим — оставить как есть.
                if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                prefs.edit { putBoolean(KEY_OWNED, false) }
            }
        }
    }
}
