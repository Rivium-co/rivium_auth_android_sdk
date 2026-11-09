package co.rivium.auth.ui

import co.rivium.auth.RiviumAuth
import co.rivium.auth.RiviumAuthErrorCode
import co.rivium.auth.RiviumNonce
import co.rivium.auth.AuthMethods
import co.rivium.auth.AuthResult
import java.net.URI
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

/** The sign-in screens. */
enum class RiviumSignInStep {
    LOADING,
    HOME,
    SIGN_UP,
    FORGOT,
    MAGIC_LINK,
    TELEGRAM_PHONE,
    TELEGRAM_CODE,
    SENT,
    UNAVAILABLE,
}

/** Which e-mail the "check your e-mail" screen is about. */
enum class RiviumSentKind { RESET, MAGIC_LINK, VERIFY }

/**
 * Holds the sign-in screen's state and runs the SDK calls. [RiviumSignInView] only reads it and calls its methods.
 * Use it from the main thread.
 */
class RiviumSignInController(
    val auth: RiviumAuth,
    val options: RiviumSignInOptions,
    val strings: RiviumSignInStrings,
    /** Called once a user is signed in. */
    var onSignedIn: ((AuthResult) -> Unit)? = null,
) {
    /** Called after every change of the state below. */
    var onChange: (() -> Unit)? = null

    var step: RiviumSignInStep = RiviumSignInStep.LOADING
        private set

    var methods: AuthMethods = AuthMethods(emptyList(), null)
        private set

    /** An action is running: everything else waits, and the screen cannot be closed. */
    var busy: Boolean = false
        private set

    /** The error code of the last failed action, or null. */
    var error: String? = null
        private set

    /** The message for [error], in the screen's language. */
    val errorText: String? get() = error?.let { strings.error(it) }

    var sent: RiviumSentKind? = null
        private set

    /** The Telegram bot link when the user has to open the bot first. */
    var botUrl: String? = null
        private set

    var email: String = ""
    var password: String = ""
    var phone: String = ""
    var code: String = ""

    /** Field errors, shown under the field. */
    var emailError: String? = null
        private set
    var passwordError: String? = null
        private set

    private var closed = false

    /** This screen set [RiviumAuth.languageCode] (it was unset) and gives it back. */
    private var languageSet = false

    val showPassword: Boolean get() = methods.isEnabled("password")
    val showMagicLink: Boolean get() = methods.isEnabled("magicLink") && !options.magicLinkRedirectUrl.isNullOrEmpty()
    val showTelegram: Boolean get() = methods.isEnabled("telegram")
    val showGoogle: Boolean get() = methods.isEnabled("google") && options.google != null
    val showApple: Boolean get() = methods.isEnabled("apple") && options.apple != null
    val showGuest: Boolean get() = methods.isEnabled("anonymous") && options.allowGuest

    /** Buttons under the e-mail form (or alone when there is no form). */
    val hasOtherMethods: Boolean get() = showMagicLink || showTelegram || showGoogle || showApple || showGuest

    val title: String
        get() = options.appName?.trim().let { app ->
            if (app.isNullOrEmpty()) strings["title"] else strings["titleTo"].replace("{app}", app)
        }

    /** Loads the project's methods and opens the first screen. Also the "Try again" of the unavailable screen. */
    suspend fun load() {
        closed = false
        // While the screen is open, the e-mails follow its language, unless the app chose a language itself.
        // Given back when the last screen closes.
        if (!languageSet) holdLanguage()
        set(step = RiviumSignInStep.LOADING)
        try {
            val loaded = auth.getMethods()
            methods = loaded
            error = null
            set(step = if (showPassword || hasOtherMethods) RiviumSignInStep.HOME else RiviumSignInStep.UNAVAILABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = RiviumSignInStrings.errorCodeOf(e)
            set(step = RiviumSignInStep.UNAVAILABLE)
        }
    }

    /** Opens [step], clearing the last error. */
    fun go(step: RiviumSignInStep) {
        error = null
        emailError = null
        passwordError = null
        if (step == RiviumSignInStep.TELEGRAM_PHONE) {
            code = ""
            botUrl = null
        }
        set(step = step)
    }

    fun back() = go(RiviumSignInStep.HOME)

    suspend fun signIn() {
        if (!checkEmail(needPassword = true)) return
        act { done(auth.signIn(email.trim(), password)) }
    }

    suspend fun signUp() {
        if (!checkEmail(needPassword = true)) return
        act {
            val r = auth.signUp(
                email.trim(),
                password,
                redirectUrl = options.verifyRedirectUrl ?: "${auth.baseUrl}/pages/verify",
            )
            if (r.signedIn) return@act done(AuthResult(r.userId, null))
            password = ""
            if (!r.verificationSent) {
                // The account exists but must be confirmed, and no e-mail went out.
                error = "VERIFY_NOT_SENT"
                set(step = RiviumSignInStep.HOME)
                return@act
            }
            sent = RiviumSentKind.VERIFY
            set(step = RiviumSignInStep.SENT)
        }
    }

    suspend fun sendReset() {
        if (!checkEmail()) return
        act {
            // No URL of the app's own: the server uses Rivium's reset page.
            auth.sendPasswordReset(email.trim(), options.resetRedirectUrl)
            sent = RiviumSentKind.RESET
            set(step = RiviumSignInStep.SENT)
        }
    }

    suspend fun sendMagicLink() {
        if (!checkEmail()) return
        val url = options.magicLinkRedirectUrl ?: return
        act {
            auth.sendMagicLink(email.trim(), url)
            sent = RiviumSentKind.MAGIC_LINK
            set(step = RiviumSignInStep.SENT)
        }
    }

    suspend fun sendTelegramCode() {
        if (busy) return
        if (phone.isBlank()) {
            error = RiviumAuthErrorCode.INVALID_INPUT
            return set()
        }
        act {
            val r = auth.requestTelegramCode(cleanPhone())
            botUrl = if (r.sent) null else r.botUrl
            if (r.sent) {
                code = ""
                set(step = RiviumSignInStep.TELEGRAM_CODE)
            } else {
                set()
            }
        }
    }

    suspend fun verifyTelegramCode() {
        if (busy) return
        if (code.isBlank()) {
            error = RiviumAuthErrorCode.INVALID_INPUT
            return set()
        }
        act { done(auth.verifyTelegramCode(cleanPhone(), code.trim())) }
    }

    suspend fun continueAsGuest() = act { done(auth.signInAnonymously()) }

    suspend fun continueWithGoogle() = act {
        val google = options.google ?: return@act
        val raw = RiviumNonce.generate()
        val idToken = google(RiviumNonce.sha256(raw)) ?: return@act // cancelled
        done(auth.signInWithGoogle(idToken, raw))
    }

    suspend fun continueWithApple() = act {
        val apple = options.apple ?: return@act
        val raw = RiviumNonce.generate()
        val c = apple(RiviumNonce.sha256(raw)) ?: return@act // cancelled
        done(auth.signInWithApple(c.idToken, raw, c.authorizationCode, c.fullName))
    }

    /** Opens the bot link through the app's [RiviumSignInOptions.openUrl]; nothing without it. */
    fun openBot() {
        val url = safeBotUrl ?: return
        options.openUrl?.invoke(url)
    }

    /** The bot link, only when it is an https Telegram address. */
    val safeBotUrl: String?
        get() {
            val u = try {
                URI(botUrl ?: return null)
            } catch (e: Exception) {
                return null
            }
            return if (u.scheme == "https" && u.host == "t.me") u.toString() else null
        }

    /** The screen is gone: forgets the password and gives back the e-mail language. */
    fun close() {
        if (closed) return
        closed = true
        password = ""
        if (!languageSet) return
        languageSet = false
        synchronized(holds) {
            val hold = holds[auth] ?: return
            if (--hold.count == 0) {
                holds.remove(auth)
                if (auth.languageCode == hold.value) auth.languageCode = null
            }
        }
    }

    private fun holdLanguage() {
        synchronized(holds) {
            val hold = holds[auth]
            val current = auth.languageCode
            if (current == null || (hold != null && current == hold.value)) {
                val h = hold ?: LanguageHold().also { holds[auth] = it }
                h.count++
                h.value = strings.languageCode
                languageSet = true
                auth.languageCode = strings.languageCode
            }
        }
    }

    private fun cleanPhone() = phone.replace(Regex("[\\s\\-()]"), "")

    private fun checkEmail(needPassword: Boolean = false): Boolean {
        if (busy) return false
        val e = email.trim()
        emailError = when {
            e.isEmpty() -> strings["required"]
            EMAIL_SHAPE.matches(e) -> null
            else -> strings["badEmail"]
        }
        passwordError = if (needPassword && password.isEmpty()) strings["required"] else null
        error = null
        set()
        return emailError == null && passwordError == null
    }

    private fun done(result: AuthResult) {
        password = ""
        if (closed) return
        onSignedIn?.invoke(result)
    }

    /** Runs one action at a time; [busy] is set before the first suspension. */
    private suspend fun act(action: suspend () -> Unit) {
        if (busy) return
        error = null
        set(busy = true)
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = RiviumSignInStrings.errorCodeOf(e)
        } finally {
            set(busy = false)
        }
    }

    private fun set(step: RiviumSignInStep? = null, busy: Boolean? = null) {
        if (step != null) this.step = step
        if (busy != null) this.busy = busy
        if (!closed) onChange?.invoke()
    }

    /** The language the open sign-in screens set on a client, and how many are open. */
    private class LanguageHold {
        var count = 0
        var value = ""
    }

    private companion object {
        val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val holds = WeakHashMap<RiviumAuth, LanguageHold>()
    }
}
