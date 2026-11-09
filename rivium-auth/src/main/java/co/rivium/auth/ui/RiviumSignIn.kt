package co.rivium.auth.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Window
import co.rivium.auth.RiviumAuth
import co.rivium.auth.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Told the signed-in user, or null when the user closed the sign-in screen. */
fun interface RiviumSignInCallback {
    fun onResult(result: AuthResult?)
}

/**
 * Opens Rivium's sign-in screen, like FirebaseUI.
 *
 * ```kotlin
 * RiviumSignIn.show(this) { result ->
 *     if (result != null) { /* signed in: result.userId */ }
 * }
 * // or, in a coroutine:
 * val result = RiviumSignIn.await(this)
 * ```
 */
object RiviumSignIn {
    /** From this screen width on the screen opens as a dialog; below it, full screen. */
    const val DIALOG_MIN_WIDTH_DP = 600

    /**
     * Opens the sign-in screen and calls [callback] once, with the signed-in user or null when the user closed it.
     * A dialog on wide screens, full screen on phones. While a sign-in runs it cannot be closed. Call on the main
     * thread.
     *
     * @return The dialog, already showing.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        auth: RiviumAuth = RiviumAuth.shared,
        options: RiviumSignInOptions = RiviumSignInOptions(),
        callback: RiviumSignInCallback,
    ): Dialog {
        val wide = activity.resources.configuration.screenWidthDp >= DIALOG_MIN_WIDTH_DP
        val palette = Palette.of(activity)
        val theme = when {
            wide && palette.dark -> android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
            wide -> android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar
            palette.dark -> android.R.style.Theme_DeviceDefault_NoActionBar
            else -> android.R.style.Theme_DeviceDefault_Light_NoActionBar
        }
        val dialog = Dialog(activity, theme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var answered = false
        fun answer(result: AuthResult?) {
            if (answered) return
            answered = true
            callback.onResult(result)
        }
        // The views use the activity's theme, so the screen looks like the app.
        val view = RiviumSignInView(activity, auth, options) { result ->
            answer(result)
            dialog.dismiss()
        }
        view.onClose = { dialog.cancel() }
        // No closing while a sign-in runs: it could land after the screen answered null.
        view.onBusyChanged = { busy ->
            dialog.setCancelable(!busy)
            dialog.setCanceledOnTouchOutside(!busy)
        }
        view.fitsSystemWindows = !wide
        dialog.setContentView(view)
        dialog.window?.let { w ->
            val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt() }
            if (wide) {
                w.setBackgroundDrawable(GradientDrawable().apply { setColor(palette.background); cornerRadius = dp(16).toFloat() })
                val width = minOf(dp(460), activity.resources.displayMetrics.widthPixels - dp(32))
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                w.setBackgroundDrawable(ColorDrawable(palette.background))
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        // The activity going away closes the screen (answers null) instead of leaking its window.
        val app = activity.application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) dialog.dismiss()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        app?.registerActivityLifecycleCallbacks(lifecycle)
        dialog.setOnDismissListener {
            app?.unregisterActivityLifecycleCallbacks(lifecycle)
            answer(null)
        }
        dialog.show()
        return dialog
    }

    /**
     * Opens the sign-in screen and answers the signed-in user, or null when the user closed it. Cancelling the
     * coroutine closes the screen.
     */
    suspend fun await(
        activity: Activity,
        auth: RiviumAuth = RiviumAuth.shared,
        options: RiviumSignInOptions = RiviumSignInOptions(),
    ): AuthResult? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val dialog = show(activity, auth, options) { result -> if (cont.isActive) cont.resume(result) }
            cont.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
        }
    }
}
