package app.simpleinsta.ig.web

import android.content.Context
import android.content.Intent

/**
 * The deliberate door to the installed Instagram app.
 *
 * This exists because the wrapper cannot be a jail: if you need to post a photo
 * or reply to a story, Instagram has to be reachable. What this class must never
 * do is make that reachable *automatically*. Instagram's own `intent://`
 * handoffs stay refused in [UrlGate]; only an explicit, held gesture gets here.
 *
 * Requires the `<queries>` entry in the manifest, or `getLaunchIntentForPackage`
 * returns null on Android 11+ regardless of whether the app is installed.
 */
object InstagramApp {

    const val PACKAGE = "com.instagram.android"

    fun isInstalled(context: Context): Boolean = launchIntent(context) != null

    /** @return true if the system accepted the launch. */
    fun open(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun launchIntent(context: Context): Intent? = runCatching {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
    }.getOrNull()
}
