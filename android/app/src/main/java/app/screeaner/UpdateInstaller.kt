package app.screeaner

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Installs an update the Mac sent, through the system installer. Android
 * asks the user to confirm and only accepts an APK signed with the same key
 * as the installed app. The result comes back to [MainActivity] (singleTask)
 * as a new intent with [ACTION_RESULT].
 */
object UpdateInstaller {
    const val ACTION_RESULT = "app.screeaner.UPDATE_RESULT"

    /** Android 8+ requires the user to allow installs from this app once. */
    fun canInstall(activity: Activity): Boolean = activity.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" setting for this app. */
    fun openPermissionSettings(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
        )
    }

    fun install(activity: Activity, apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("screeaner.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(activity, MainActivity::class.java).setAction(ACTION_RESULT)
            // Mutable: the installer adds the status extras to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getActivity(activity, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Handles an [ACTION_RESULT] intent. Returns a message for the user, or
     * null when the system confirmation screen was shown instead.
     */
    fun handleResult(activity: Activity, intent: Intent): String? {
        return when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    null
                } else {
                    activity.getString(R.string.update_failed, "?")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> activity.getString(R.string.update_done)
            PackageInstaller.STATUS_FAILURE_ABORTED -> activity.getString(R.string.update_cancelled)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                activity.getString(R.string.update_signature)
            else -> activity.getString(
                R.string.update_failed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?",
            )
        }
    }
}
