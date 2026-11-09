package co.rivium.auth.ui

import co.rivium.auth.RiviumAuth
import co.rivium.auth.AuthResult
import co.rivium.auth.MemorySessionStorage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The sign-in screen against a real Rivium Auth project. Skipped unless these are set:
 * `RIVIUM_AUTH_LIVE_KEY` (a project with anonymous, e-mail and password without a required confirmation, and magic
 * link on), `RIVIUM_AUTH_LIVE_BASE`, `RIVIUM_AUTH_LIVE_EMAIL` (a new address), `RIVIUM_AUTH_LIVE_PASSWORD` and
 * `RIVIUM_AUTH_LIVE_MAGIC_URL` (one of the project's redirect URLs).
 */
class SignInLiveTest {
    private val key = System.getenv("RIVIUM_AUTH_LIVE_KEY").orEmpty()
    private val base = System.getenv("RIVIUM_AUTH_LIVE_BASE").orEmpty()
    private val email = System.getenv("RIVIUM_AUTH_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("RIVIUM_AUTH_LIVE_PASSWORD").orEmpty()
    private val magicUrl = System.getenv("RIVIUM_AUTH_LIVE_MAGIC_URL").orEmpty()

    @Test
    fun signUpWrongPasswordSignInResetMagicLinkGuest() = runBlocking {
        assumeTrue("set RIVIUM_AUTH_LIVE_* to run", key.isNotEmpty() && base.isNotEmpty() && email.isNotEmpty())
        val auth = RiviumAuth().apply { configure(key, base, OkHttpClient(), MemorySessionStorage()) }
        var result: AuthResult? = null
        // The screens' language for the run, and the texts expected in it.
        var screenLanguage = "en"
        var expectedTitle = "Sign in to Tikashop"
        var expectedWrong = "Wrong e-mail or password."

        suspend fun open(lang: String = screenLanguage): RiviumSignInController {
            result = null
            val c = RiviumSignInController(
                auth,
                RiviumSignInOptions(languageCode = lang, appName = "Tikashop", allowGuest = true, magicLinkRedirectUrl = magicUrl),
                RiviumSignInStrings.of(lang),
            ) { result = it }
            c.load()
            assertEquals(c.errorText, RiviumSignInStep.HOME, c.step)
            return c
        }

        // Only the methods the project turned on (password, magic link, anonymous; Telegram off).
        var c = open()
        assertTrue(c.showPassword && c.showMagicLink && c.showGuest)
        assertFalse(c.showTelegram)
        assertEquals(expectedTitle, c.title)
        assertEquals(screenLanguage, auth.languageCode)

        c.go(RiviumSignInStep.SIGN_UP)
        c.email = email
        c.password = password
        c.signUp()
        val id = result?.userId
        assertTrue("sign-up: ${c.errorText}", id != null)
        println("signed up: $id")
        c.close()
        auth.signOut()

        c = open()
        c.email = email
        c.password = "not-the-password-1"
        c.signIn()
        assertEquals("INVALID_CREDENTIALS", c.error)
        assertEquals(expectedWrong, c.errorText)
        assertNull(result)
        println("wrong password: ${c.errorText}")
        c.password = password
        c.signIn()
        assertEquals(c.errorText, id, result?.userId)
        assertEquals(id, auth.currentUserId)
        println("signed in again: same user")
        c.close()
        auth.signOut()

        c = open()
        c.email = email
        c.go(RiviumSignInStep.FORGOT)
        c.sendReset()
        assertEquals(c.errorText, RiviumSignInStep.SENT, c.step)
        assertEquals(RiviumSentKind.RESET, c.sent)
        println("reset sent")
        c.close()

        c = open()
        c.go(RiviumSignInStep.MAGIC_LINK)
        c.email = email
        c.sendMagicLink()
        assertEquals(c.errorText, RiviumSignInStep.SENT, c.step)
        assertEquals(RiviumSentKind.MAGIC_LINK, c.sent)
        println("magic link sent")
        c.close()

        c = open("en")
        c.continueAsGuest()
        val guest = result?.userId
        assertTrue("guest: ${c.errorText}", guest != null)
        assertNotEquals(id, guest)
        assertTrue(auth.me().isAnonymous)
        println("guest: $guest")
        c.close()
        assertNull("the e-mail language is given back", auth.languageCode)
    }
}
