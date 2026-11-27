package io.maru.manime.disguise

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object AppDisguiseManager {
    private const val PREFS_NAME = "nanami_app_disguise"
    private const val KEY_DISGUISE_ENABLED = "disguise_enabled"

    fun isDisguiseEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISGUISE_ENABLED, false)

    /**
     * Switches between default Nanami branding and the disguised app icon/label alias.
     * Enables the target component BEFORE disabling the current one so the OS launcher
     * never observes zero launcher components during transition.
     */
    fun applyDisguise(
        context: Context,
        enabled: Boolean,
        defaultAliasClassName: String,
        disguiseAliasClassName: String,
        defaultLabel: String,
        disguiseLabel: String,
    ) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISGUISE_ENABLED, enabled)
            .apply()

        val pm = appContext.packageManager
        val enableTarget = ComponentName(
            appContext.packageName,
            if (enabled) disguiseAliasClassName else defaultAliasClassName,
        )
        val disableTarget = ComponentName(
            appContext.packageName,
            if (enabled) defaultAliasClassName else disguiseAliasClassName,
        )

        runCatching {
            pm.setComponentEnabledSetting(
                enableTarget,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                disableTarget,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        (context as? Activity)?.let { activity ->
            syncTaskDescription(activity, enabled, defaultLabel, disguiseLabel)
        }
    }

    @Suppress("DEPRECATION")
    fun syncTaskDescription(
        activity: Activity,
        disguiseEnabled: Boolean = isDisguiseEnabled(activity),
        defaultLabel: String,
        disguiseLabel: String,
    ) {
        val label = if (disguiseEnabled) disguiseLabel else defaultLabel
        runCatching {
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(label)
                    .build()
            } else {
                ActivityManager.TaskDescription(label)
            }
            activity.setTaskDescription(desc)
        }
    }
}
