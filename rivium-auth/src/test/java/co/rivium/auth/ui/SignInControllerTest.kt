package co.rivium.auth.ui

import co.rivium.auth.RiviumAuth
import co.rivium.auth.RiviumNonce
import co.rivium.auth.AuthResult
import co.rivium.auth.ui.FakeAuthServer.Companion.err
import co.rivium.auth.ui.FakeAuthServer.Companion.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SignInControllerTest {
    private val servers = mutableListOf<FakeAuthServer>()
    private val results = mutableListOf<AuthResult>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    private fun fake(vararg methods: String, bot: String? = null) = FakeAuthServer(methods.toList(), bot).also { servers += it }

    private fun controller(
        auth: RiviumAuth,
        options: RiviumSignInOptions = RiviumSignInOptions(),
        language: String? = options.languageCode,
    ) = RiviumSignInController(auth, options, RiviumSignInStrings.of(language)) { results += it }

    // ------------------------------------------------------------ 1. Methods

    @Test
    fun showsOnlyTheMethodsThatAreOnAndThatTheAppCanRun() = runBlocking {
        val f = fake("password", "magicLink", "telegram", "google", "apple", "anonymous")
        val bare = controller(f.client())
        bare.load()
        assertEquals(RiviumSignInStep.HOME, bare.step)
        assertTrue(bare.showPassword)
        assertTrue(bare.showTelegram)
        assertFalse("no app function", bare.showGoogle)
        assertFalse("no app function", bare.showApple)
        assertFalse("no redirect URL", bare.showMagicLink)
        assertFalse("guest is off by default", bare.showGuest)

        val full = controller(
            f.client(),
            RiviumSignInOptions(
                allowGuest = true,
                magicLinkRedirectUrl = "https://app.example.com/auth",
                google = { "id" },
                apple = { RiviumAppleCredential("id") },
            ),
        )
        full.load()
        assertTrue(full.showGoogle && full.showApple && full.showMagicLink && full.showGuest && full.showTelegram)

        val off = fake("anonymous")
        val noGuest = controller(off.client())
        noGuest.load()
        assertEquals("guest is the only method but not allowed", RiviumSignInStep.UNAVAILABLE, noGuest.step)
    }

    @Test
    fun noPasswordMethodNoEmailForm() = runBlocking {
        val c = controller(fake("telegram").client())
        c.load()
        assertEquals(RiviumSignInStep.HOME, c.step)
        assertFalse(c.showPassword)
        assertTrue(c.hasOtherMethods)
    }

    @Test
    fun nothingOnSaysNotAvailable() = runBlocking {
        val c = controller(fake().client())
        c.load()
        assertEquals(RiviumSignInStep.UNAVAILABLE, c.step)
        assertNull(c.errorText)
        assertEquals("Sign-in is not available right now.", c.strings["noMethods"])
    }

    @Test
    fun noAnswerShowsNetworkErrorAndTryAgainWorks() = runBlocking {
        val f = fake("password")
        val auth = f.client()
        f.server.shutdown()
        val c = controller(auth)
        c.load()
        assertEquals(RiviumSignInStep.UNAVAILABLE, c.step)
        assertEquals("NETWORK_ERROR", c.error)
        assertEquals("No connection. Check your internet and try again.", c.errorText)

        val g = fake("password")
        g.methodsDown = true
        val c2 = controller(g.client())
        c2.load()
        assertEquals(RiviumSignInStep.UNAVAILABLE, c2.step)
        assertEquals("Something went wrong. Try again.", c2.errorText)
        g.methodsDown = false
        c2.load() // Try again
        assertEquals(RiviumSignInStep.HOME, c2.step)
        assertNull(c2.error)
    }

    // ------------------------------------------------------------ 2. Field checks

    @Test
    fun fieldsAreCheckedBeforeAnyRequest() = runBlocking {
        val f = fake("password")
        val c = controller(f.client())
        c.load()
        c.signIn()
        assertEquals("Required", c.emailError)
        assertEquals("Required", c.passwordError)
        c.email = "not-an-email"
        c.password = "x"
        c.signIn()
        assertEquals("Enter a valid e-mail", c.emailError)
        assertNull(c.passwordError)
        c.go(RiviumSignInStep.FORGOT)
        c.email = "  "
        c.sendReset()
        assertEquals("Required", c.emailError)
        assertEquals(listOf("/v1/methods"), f.requests.map { it.first.path })
    }

    // ------------------------------------------------------------ Sign-in

    @Test
    fun emailSignInCallsTheSdkAndReportsTheUser() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-in"] = { f.session("u1") }
        val auth = f.client()
        val c = controller(auth)
        c.load()
        c.email = " ali@example.com "
        c.password = "a-good-password"
        c.signIn()
        assertEquals(listOf("u1"), results.map { it.userId })
        assertEquals("ali@example.com", f.bodies("/v1/password/sign-in").single().getString("email"))
        assertEquals("u1", auth.currentUserId)
        assertEquals("the password is not kept", "", c.password)
        assertFalse(c.busy)
    }

    @Test
    fun wrongPasswordShowsMessageFromTheCodeNotTheServerHint() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-in"] = { err("INVALID_CREDENTIALS", 401) }
        val c = controller(f.client())
        c.load()
        c.email = "ali@example.com"
        c.password = "wrong-password"
        c.signIn()
        assertEquals("INVALID_CREDENTIALS", c.error)
        assertEquals("Wrong e-mail or password.", c.errorText)
        assertFalse(c.errorText!!.contains("hint"))
        assertTrue(results.isEmpty())
        assertEquals(RiviumSignInStep.HOME, c.step)
    }

    @Test
    fun unknownCodeAndNonRiviumErrorsShowTheGenericMessage() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-in"] = { err("SOMETHING_NEW", 400) }
        val c = controller(f.client(), RiviumSignInOptions(google = { throw IllegalStateException("app bug") }))
        c.load()
        c.email = "ali@example.com"
        c.password = "x"
        c.signIn()
        assertEquals("Something went wrong. Try again.", c.errorText)
        assertEquals("Something went wrong. Try again.", RiviumSignInStrings.of("en").error(RiviumSignInStrings.errorCodeOf(IllegalStateException())))
    }


    @Test
    fun aLanguageTheScreenDoesNotHaveOrAnOddCodeShowsEnglish() {
        for (code in listOf("de", "de-DE", null, "", "toString")) {
            val s = RiviumSignInStrings.of(code)
            assertEquals("en", s.languageCode)
            assertEquals("Sign in", s["title"])
            assertFalse(s.rtl)
        }
    }

    // ------------------------------------------------------------ 3. Sign-up

    @Test
    fun signUpThatSignsInAtOnce() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-up"] = {
            json(JSONObject().put("userId", "u9").put("verificationSent", false).put("session", JSONObject(f.session("u9").getBody()!!.readUtf8())))
        }
        val c = controller(f.client())
        c.load()
        c.go(RiviumSignInStep.SIGN_UP)
        c.email = "new@example.com"
        c.password = "a-good-password"
        c.signUp()
        assertEquals(listOf("u9"), results.map { it.userId })
        assertEquals("", c.password)
    }

    @Test
    fun signUpThatNeedsAConfirmedEmailLinksToTheHostedConfirmPage() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-up"] = { json(JSONObject().put("userId", "u9").put("verificationSent", true).put("session", JSONObject.NULL)) }
        val auth = f.client()
        val c = controller(auth)
        c.load()
        c.go(RiviumSignInStep.SIGN_UP)
        c.email = "new@example.com"
        c.password = "a-good-password"
        c.signUp()
        assertEquals("${f.baseUrl}/pages/verify", f.bodies("/v1/password/sign-up").single().getString("redirectUrl"))
        assertEquals("${auth.baseUrl}/pages/verify", f.bodies("/v1/password/sign-up").single().getString("redirectUrl"))
        assertEquals(RiviumSignInStep.SENT, c.step)
        assertEquals(RiviumSentKind.VERIFY, c.sent)
        assertEquals("", c.password)
        assertTrue(results.isEmpty())

        val own = controller(auth, RiviumSignInOptions(verifyRedirectUrl = "https://app.example.com/verify"))
        own.load()
        own.email = "new2@example.com"
        own.password = "a-good-password"
        own.signUp()
        assertEquals("https://app.example.com/verify", f.bodies("/v1/password/sign-up").last().getString("redirectUrl"))
    }

    @Test
    fun signUpThatNeedsAConfirmedEmailButSentNoneSaysSoAndClearsThePassword() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-up"] = { json(JSONObject().put("userId", "u9").put("verificationSent", false).put("session", JSONObject.NULL)) }
        val c = controller(f.client())
        c.load()
        c.go(RiviumSignInStep.SIGN_UP)
        c.email = "new@example.com"
        c.password = "a-good-password"
        c.signUp()
        assertEquals(RiviumSignInStep.HOME, c.step)
        assertEquals("VERIFY_NOT_SENT", c.error)
        assertTrue(c.errorText!!.contains("could not be sent"))
        assertEquals("", c.password)
        assertTrue(results.isEmpty())
    }

    // ------------------------------------------------------------ 4. Forgot password

    @Test
    fun forgotPasswordWithoutTheAppsUrlLetsTheServerPickItsPage() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/forgot"] = { MockResponse().setResponseCode(202) }
        val c = controller(f.client())
        c.load()
        c.go(RiviumSignInStep.FORGOT)
        c.email = "ali@example.com"
        c.sendReset()
        val body = f.bodies("/v1/password/forgot").single()
        assertEquals("ali@example.com", body.getString("email"))
        assertFalse("no redirectUrl at all", body.has("redirectUrl"))
        assertEquals(RiviumSignInStep.SENT, c.step)
        assertEquals(RiviumSentKind.RESET, c.sent)

        val own = controller(f.client(), RiviumSignInOptions(resetRedirectUrl = "https://app.example.com/reset"))
        own.load()
        own.email = "ali@example.com"
        own.sendReset()
        assertEquals("https://app.example.com/reset", f.bodies("/v1/password/forgot").last().getString("redirectUrl"))
    }

    // ------------------------------------------------------------ Magic link

    @Test
    fun magicLinkUsesTheAppsUrl() = runBlocking {
        val f = fake("password", "magicLink")
        f.answers["/v1/magic-link/send"] = { MockResponse().setResponseCode(202) }
        val c = controller(f.client(), RiviumSignInOptions(magicLinkRedirectUrl = "https://app.example.com/auth"))
        c.load()
        assertTrue(c.showMagicLink)
        c.go(RiviumSignInStep.MAGIC_LINK)
        c.email = "ali@example.com"
        c.sendMagicLink()
        assertEquals("https://app.example.com/auth", f.bodies("/v1/magic-link/send").single().getString("redirectUrl"))
        assertEquals(RiviumSentKind.MAGIC_LINK, c.sent)
        assertEquals(RiviumSignInStep.SENT, c.step)
    }

    // ------------------------------------------------------------ 5. Telegram

    @Test
    fun telegramOpenTheBotFirstThenTheCode() = runBlocking {
        val f = fake("telegram", bot = "rivium_bot")
        var sent = false
        f.answers["/v1/telegram/code"] = {
            json(
                if (sent) JSONObject().put("sent", true).put("botUrl", JSONObject.NULL).put("expiresIn", 300)
                else JSONObject().put("sent", false).put("botUrl", "https://t.me/rivium_bot").put("expiresIn", 300),
            )
        }
        f.answers["/v1/telegram/verify"] = { f.session("tg1") }
        val opened = mutableListOf<String>()
        val c = controller(f.client(), RiviumSignInOptions(openUrl = { opened += it }))
        c.load()
        c.go(RiviumSignInStep.TELEGRAM_PHONE)
        c.sendTelegramCode()
        assertEquals("INVALID_INPUT", c.error)
        assertTrue(f.calls("/v1/telegram/code").isEmpty())

        c.phone = "+98 (912) 345-6789"
        c.sendTelegramCode()
        assertEquals("+989123456789", f.bodies("/v1/telegram/code").last().getString("phone"))
        assertEquals(RiviumSignInStep.TELEGRAM_PHONE, c.step)
        assertEquals("https://t.me/rivium_bot", c.safeBotUrl)
        assertEquals("@rivium_bot", RiviumSignInView.botName(c.safeBotUrl!!))
        c.openBot()
        assertEquals(listOf("https://t.me/rivium_bot"), opened)

        sent = true
        c.sendTelegramCode()
        assertEquals(RiviumSignInStep.TELEGRAM_CODE, c.step)
        assertNull(c.safeBotUrl)
        c.code = " 123456 "
        c.verifyTelegramCode()
        val body = f.bodies("/v1/telegram/verify").single()
        assertEquals("+989123456789", body.getString("phone"))
        assertEquals("123456", body.getString("code"))
        assertEquals(listOf("tg1"), results.map { it.userId })
    }

    @Test
    fun aBotLinkThatIsNotHttpsTmeIsNeverShownOrOpened() = runBlocking {
        for (bad in listOf("http://t.me/rivium_bot", "https://t.me.evil.example/x", "https://evil.example/t.me", "javascript:alert(1)", "tg://resolve?domain=x")) {
            val f = fake("telegram")
            f.answers["/v1/telegram/code"] = { json(JSONObject().put("sent", false).put("botUrl", bad).put("expiresIn", 300)) }
            val opened = mutableListOf<String>()
            val c = controller(f.client(), RiviumSignInOptions(openUrl = { opened += it }))
            c.load()
            c.go(RiviumSignInStep.TELEGRAM_PHONE)
            c.phone = "+989123456789"
            c.sendTelegramCode()
            assertNull(bad, c.safeBotUrl)
            c.openBot()
            assertTrue(bad, opened.isEmpty())
        }
    }

    // ------------------------------------------------------------ 6. Google / Apple

    @Test
    fun googleTheAppGetsTheHashedNonceRiviumTheRawOne() = runBlocking {
        val f = fake("google")
        f.answers["/v1/google"] = { f.session("g1") }
        val hashed = mutableListOf<String>()
        val c = controller(f.client(), RiviumSignInOptions(google = { n -> hashed += n; "google-id-token" }))
        c.load()
        c.continueWithGoogle()
        c.continueWithGoogle()
        val bodies = f.bodies("/v1/google")
        assertEquals(2, bodies.size)
        bodies.forEachIndexed { i, b ->
            assertEquals("google-id-token", b.getString("idToken"))
            assertEquals(RiviumNonce.sha256(b.getString("nonce")), hashed[i])
            assertNotEquals(hashed[i], b.getString("nonce"))
        }
        assertNotEquals("a fresh nonce per attempt", hashed[0], hashed[1])
        assertEquals(listOf("g1", "g1"), results.map { it.userId })
    }

    @Test
    fun appleTheAppGetsTheHashedNonceRiviumTheRawOne() = runBlocking {
        val f = fake("apple")
        f.answers["/v1/apple"] = { f.session("a1") }
        var hashed = ""
        val c = controller(f.client(), RiviumSignInOptions(apple = { n -> hashed = n; RiviumAppleCredential("apple-id", "code-1", "Ali") }))
        c.load()
        c.continueWithApple()
        val b = f.bodies("/v1/apple").single()
        assertEquals(RiviumNonce.sha256(b.getString("nonce")), hashed)
        assertEquals("code-1", b.getString("authorizationCode"))
        assertEquals("Ali", b.getString("name"))
        assertEquals(listOf("a1"), results.map { it.userId })
    }

    @Test
    fun googleOrAppleCancelledSendsNothingAndShowsNoError() = runBlocking {
        val f = fake("google", "apple")
        val c = controller(f.client(), RiviumSignInOptions(google = { null }, apple = { null }))
        c.load()
        c.continueWithGoogle()
        c.continueWithApple()
        assertNull(c.error)
        assertFalse(c.busy)
        assertTrue(f.calls("/v1/google").isEmpty() && f.calls("/v1/apple").isEmpty())
        assertTrue(results.isEmpty())
    }

    // ------------------------------------------------------------ Guest

    @Test
    fun continueAsGuest() = runBlocking {
        val f = fake("anonymous")
        f.answers["/v1/anonymous"] = { f.session("anon1") }
        val c = controller(f.client(), RiviumSignInOptions(allowGuest = true))
        c.load()
        assertEquals(RiviumSignInStep.HOME, c.step)
        c.continueAsGuest()
        assertEquals(listOf("anon1"), results.map { it.userId })
    }

    // ------------------------------------------------------------ 7. One action at a time

    @Test
    fun oneActionAtATimeAndBusyIsSetBeforeTheFirstAwait() = runBlocking {
        val f = fake("password", "anonymous")
        f.answers["/v1/password/sign-in"] = { f.session("u1") }
        f.answers["/v1/anonymous"] = { f.session("anon") }
        val gate = CountDownLatch(1)
        f.gate = gate
        val c = controller(f.client(), RiviumSignInOptions(allowGuest = true))
        c.load()
        c.email = "ali@example.com"
        c.password = "a-good-password"
        val first = launch(Dispatchers.IO) { c.signIn() }
        while (!c.busy) delay(5)
        c.continueAsGuest()
        c.signIn()
        assertTrue(c.busy)
        gate.countDown()
        first.join()
        assertFalse(c.busy)
        assertEquals(1, f.calls("/v1/password/sign-in").size)
        assertTrue(f.calls("/v1/anonymous").isEmpty())
        assertEquals(listOf("u1"), results.map { it.userId })
    }

    @Test
    fun aClosedScreenNeverReportsASignIn() = runBlocking {
        val f = fake("password")
        f.answers["/v1/password/sign-in"] = { f.session("u1") }
        val gate = CountDownLatch(1)
        f.gate = gate
        val c = controller(f.client())
        c.load()
        c.email = "ali@example.com"
        c.password = "a-good-password"
        val job = async(Dispatchers.IO) { c.signIn() }
        while (!c.busy) delay(5)
        c.close()
        assertEquals("", c.password)
        gate.countDown()
        job.await()
        assertTrue(results.isEmpty())
    }

    // ------------------------------------------------------------ 9. E-mail language

    @Test
    fun theEmailsFollowTheOpenScreenGivenBackOnCloseNeverTheAppsOwnChoice() = runBlocking {
        val f = fake("password")
        val auth = f.client()
        val c = controller(auth, RiviumSignInOptions(languageCode = "en"))
        c.load()
        assertEquals("en", auth.languageCode)
        c.close()
        assertNull(auth.languageCode)

        auth.languageCode = "en-US"
        val d = controller(auth, RiviumSignInOptions(languageCode = "en"))
        d.load()
        assertEquals("the app's own choice is kept", "en-US", auth.languageCode)
        d.close()
        assertEquals("en-US", auth.languageCode)

        auth.languageCode = null
        val e = controller(auth, language = "en") // the app's language, no languageCode option
        e.load()
        assertEquals("en", auth.languageCode)
        auth.languageCode = "de" // the app changed it while the screen was open
        e.close()
        assertEquals("never undone over the app's change", "de", auth.languageCode)
    }

    @Test
    fun twoOverlappingScreensKeepTheLanguageUntilTheLastCloses() = runBlocking {
        val f = fake("password")
        val auth = f.client()
        val first = controller(auth, RiviumSignInOptions(languageCode = "en"))
        first.load()
        val second = controller(auth, RiviumSignInOptions(languageCode = "en")) // opens before the first closes
        second.load()
        first.close()
        assertEquals("en", auth.languageCode)
        first.close() // twice is harmless
        assertEquals("en", auth.languageCode)
        second.close()
        assertNull(auth.languageCode)

        // Each client keeps its own count.
        val other = f.client()
        val a = controller(auth, RiviumSignInOptions(languageCode = "en"))
        val b = controller(other, RiviumSignInOptions(languageCode = "en"))
        a.load()
        b.load()
        b.close()
        assertEquals("en", auth.languageCode)
        assertNull(other.languageCode)
        a.close()
        assertNull(auth.languageCode)
    }
}
