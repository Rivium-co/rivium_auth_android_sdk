package co.rivium.auth.ui

import co.rivium.auth.RiviumAuthException

/** The sign-in screen's texts: English, and each language in [LANGUAGES]. */
class RiviumSignInStrings private constructor(
    /** The language whose texts are shown: one the screen has (see [LANGUAGES]), English for any other. */
    val languageCode: String,
    private val table: Map<String, String>,
    /** Whether the language reads right to left. */
    val rtl: Boolean,
) {
    operator fun get(key: String): String = table[key] ?: EN[key] ?: key

    /** The user-facing message for an error code; never the server's English hint. */
    fun error(code: String): String = table["error.$code"] ?: EN["error.$code"] ?: get("error.other")

    companion object {
        /** The texts of [languageCode] when the screen has them, English for everything else. */
        @JvmStatic
        fun of(languageCode: String?): RiviumSignInStrings {
            val code = (languageCode ?: "").trim().lowercase().split('-', '_').first()
            return LANGUAGES[code] ?: LANGUAGES.getValue("en")
        }

        /** The error code of [e]; `unknown` (shown as `error.other`) for anything that is not an Rivium Auth error. */
        @JvmStatic
        fun errorCodeOf(e: Throwable): String = (e as? RiviumAuthException)?.code ?: "unknown"

        private val EN = mapOf(
        "title" to "Sign in",
        "titleTo" to "Sign in to {app}",
        "signUpTitle" to "Create an account",
        "forgotTitle" to "Reset your password",
        "magicTitle" to "Sign in with a link",
        "telegramTitle" to "Sign in with Telegram",
        "email" to "E-mail",
        "password" to "Password",
        "phone" to "Phone number",
        "phoneHint" to "+98 912 345 6789",
        "code" to "Code",
        "signIn" to "Sign in",
        "signUp" to "Create account",
        "noAccount" to "No account? Create one",
        "haveAccount" to "Have an account? Sign in",
        "forgot" to "Forgot password?",
        "sendReset" to "Send reset link",
        "sendLink" to "Send sign-in link",
        "sendCode" to "Send code",
        "verify" to "Verify",
        "or" to "or",
        "withEmailLink" to "E-mail me a sign-in link",
        "withTelegram" to "Continue with Telegram",
        "withGoogle" to "Continue with Google",
        "withApple" to "Continue with Apple",
        "guest" to "Continue as guest",
        "back" to "Back",
        "close" to "Close",
        "retry" to "Try again",
        "checkEmail" to "Check your e-mail",
        "resetSent" to "If {email} has an account, a link to choose a new password is on its way.",
        "linkSent" to "We sent a sign-in link to {email}. Open it on this device.",
        "verifySent" to "We sent a confirmation link to {email}. Confirm your e-mail, then sign in.",
        "codeSent" to "Enter the code Telegram sent you.",
        "botFirst" to "Open {bot} in Telegram once and share your phone number, then try again.",
        "noMethods" to "Sign-in is not available right now.",
        "required" to "Required",
        "badEmail" to "Enter a valid e-mail",
        "error.INVALID_CREDENTIALS" to "Wrong e-mail or password.",
        "error.EMAIL_TAKEN" to "This e-mail already has an account. Sign in instead.",
        "error.EMAIL_NOT_VERIFIED" to "Confirm your e-mail first. Check your inbox.",
        "error.WEAK_PASSWORD" to "Choose a longer password that is not a common one.",
        "error.INVALID_INPUT" to "Check what you entered.",
        "error.TOKEN_INVALID" to "This code is wrong or has expired.",
        "error.RATE_LIMITED" to "Too many tries. Wait a little and try again.",
        "error.USER_DISABLED" to "This account is disabled.",
        "error.METHOD_DISABLED" to "This sign-in method is turned off.",
        "error.PRODUCT_DISABLED" to "Sign-in is not available right now.",
        "error.MAU_LIMIT" to "Sign-in is not available right now.",
        "error.NETWORK_ERROR" to "No connection. Check your internet and try again.",
        "error.PROVIDER_ALREADY_LINKED" to "This account is linked to another user.",
        "error.INVALID_ID_TOKEN" to "Sign-in could not be confirmed. Try again.",
        "error.VERIFY_NOT_SENT" to "Your account was created, but the confirmation e-mail could not be sent. Try again later with “Forgot password?”.",
        "error.other" to "Something went wrong. Try again.",
        )


        /** The languages of the screen, by the part of a language code before "-" or "_" ("en-US" → "en"). */
        private val LANGUAGES: Map<String, RiviumSignInStrings> = mapOf(
            "en" to RiviumSignInStrings("en", EN, rtl = false),
        )
    }
}
