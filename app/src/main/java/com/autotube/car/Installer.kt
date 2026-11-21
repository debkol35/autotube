package com.autotube.car

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File
import java.io.FileNotFoundException

/**
 * Android Auto (17.7+) only lists full-screen "projection" apps whose install record says
 * Play Store, and only trusts that record when the system package installer made the install.
 * So we hand our own APK to the system installer and ask it to record Play Store as installer
 * (the same trick Fermata Auto and KingInstaller use). Updates must be installed the same way.
 */
object Installer {
    const val PLAY = "com.android.vending"
    const val SYSTEM_INSTALLER = "com.google.android.packageinstaller"
    const val APK_MIME = "application/vnd.android.package-archive"

    data class Source(val installer: String?, val initiator: String?) {
        val playRecorded get() = installer == PLAY
        val viaSystemInstaller get() = initiator == null || initiator == SYSTEM_INSTALLER
        val ok get() = playRecorded && viaSystemInstaller
    }

    fun source(ctx: Context): Source {
        val pm = ctx.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val i = pm.getInstallSourceInfo(ctx.packageName)
                Source(i.installingPackageName, i.initiatingPackageName)
            } else {
                @Suppress("DEPRECATION")
                Source(pm.getInstallerPackageName(ctx.packageName), null)
            }
        } catch (e: Exception) {
            Source(null, null)
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
    }

    /** Opens the system installer for [apk] with Play Store recorded as installer. */
    fun install(a: Activity, apk: Uri) {
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(apk, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY)
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        a.startActivity(i)
    }

    fun selfApk(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.apk/${ApkProvider.SELF}")
}

/** Serves AutoTube's own installed APK (read-only, nothing else) to the system installer. */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        return when (uri.lastPathSegment) {
            SELF -> File(ctx.applicationInfo.sourceDir)
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = Installer.APK_MIME

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "AutoTube.apk"
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val SELF = "self.apk"
    }
}
