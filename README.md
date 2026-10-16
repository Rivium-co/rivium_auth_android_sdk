<p align="center">
  <a href="https://rivium.co">
    <img src="https://rivium.co/logo.png" alt="Rivium" width="120" />
  </a>
</p>

<h3 align="center">Rivium Auth Android SDK</h3>

<p align="center">
  Sign-in for your Android app's users: anonymous, Google, Apple, e-mail and password, magic link and Telegram, with a user token every Rivium SDK accepts.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/co.rivium/rivium-auth"><img src="https://img.shields.io/maven-central/v/co.rivium/rivium-auth" alt="Maven Central" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-23+-3DDC84?logo=android&logoColor=white" alt="API 23+" />
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" />
</p>

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("co.rivium:rivium-auth:0.1.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'co.rivium:rivium-auth:0.1.0'
}
```

The SDK needs the `INTERNET` permission, which it declares itself.

## Quick start

Turn Rivium Auth on for your project in the Rivium Console and enable the
sign-in methods you want. Then, in `Application.onCreate`:

```kotlin
RiviumAuth.shared.configure(this, "rv_live_YOUR_PUBLIC_KEY")
```

A session from an earlier launch is restored automatically. All sign-in calls
are `suspend` functions:

```kotlin
lifecycleScope.launch {
    RiviumAuth.shared.signInAnonymously()
    Log.d("Auth", "user: ${RiviumAuth.shared.currentUserId}")
}
```

## Sign in

```kotlin
val auth = RiviumAuth.shared

auth.signInAnonymously()
auth.signIn("ana@example.com", "a-long-password")
val result = auth.signUp("ana@example.com", "a-long-password", redirectUrl = "https://example.com/verify")

// Magic link: send it, then sign in with the link the app is opened with.
auth.sendMagicLink("ana@example.com", redirectUrl = "https://example.com/magic")
RiviumAuth.tokenFromLink(intent.dataString ?: "")?.let { auth.signInWithMagicLink(it) }

// Telegram: the code arrives in the project's bot.
val code = auth.requestTelegramCode("+37491234567")
if (!code.sent) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.botUrl)))
auth.verifyTelegramCode("+37491234567", "123456")
```

Show only the methods the project offers:

```kotlin
val methods = auth.getMethods()
if (methods.isEnabled("google")) { /* show the Google button */ }
```

### Sign in with Google

Use Credential Manager with your **web** client id and a hashed nonce. The
nonce is required (16-256 characters; `RiviumNonce.generate()` makes one) and
works once. Add
`androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth`
and `com.google.android.libraries.identity.googleid:googleid` to your app:

```kotlin
val rawNonce = RiviumNonce.generate()
val option = GetGoogleIdOption.Builder()
    .setServerClientId(WEB_CLIENT_ID)
    .setFilterByAuthorizedAccounts(false)
    .setNonce(RiviumNonce.sha256(rawNonce))
    .build()
val response = CredentialManager.create(context)
    .getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
val idToken = GoogleIdTokenCredential.createFrom(response.credential.data).idToken

auth.signInWithGoogle(idToken, rawNonce)
```

Add the client id to the project's Google settings in the Console.

### Sign in with Apple

Run Apple's web sign-in with your Services ID and `RiviumNonce.sha256(rawNonce)`
as the nonce, then pass the `id_token` and the `code` Apple returns:

```kotlin
auth.signInWithApple(idToken, rawNonce, authorizationCode = code)
```

## Upgrade an anonymous user

Linking keeps the user's id, so everything they created stays theirs:

```kotlin
try {
    auth.linkGoogle(idToken, rawNonce)   // or linkApple(...), linkPassword(email, password)
} catch (e: RiviumAuthException) {
    if (e.code == RiviumAuthErrorCode.PROVIDER_ALREADY_LINKED) {
        // This Google account already belongs to another user.
    }
}
```

## Use with other Rivium SDKs

Rivium Sync, Chat and Push trust the signed-in user through a token provider:

```kotlin
val auth = RiviumAuth.shared

// Sync
RiviumSyncConfig.builder(apiKey).userTokenProvider(auth.blockingTokenProvider).build()

// Push
RiviumPushConfig(apiKey = apiKey, tokenProvider = auth.tokenProvider)

// Chat
RiviumChatConfig(apiKey = apiKey, userId = auth.currentUserId!!, tokenProvider = auth.strictTokenProvider)
```

`tokenProvider` returns null when nobody is signed in; `strictTokenProvider`
throws instead; `blockingTokenProvider` blocks the calling thread and must not
run on the main thread. All return a token that is refreshed before it expires.

## Session

```kotlin
auth.currentUserId                 // null when signed out
val user = auth.me()               // profile: anonymous, providers, ...

auth.authState.collect { userId -> /* sign-in, sign-out, session end */ }
val registration = auth.addAuthStateListener { userId -> }
registration.remove()

auth.signOut()                     // this device
auth.signOutEverywhere()           // every device
auth.deleteAccount()
```

The refresh token is stored encrypted with a key that never leaves the
Android Keystore; the access token stays in memory. A session revoked
elsewhere signs the user out here; a network error does not.

## Errors

Every error is a `RiviumAuthException`. Branch on `code` (see
`RiviumAuthErrorCode`: `INVALID_CREDENTIALS`, `EMAIL_TAKEN`, `WEAK_PASSWORD`,
`RATE_LIMITED` with `retryAfterSec`, `PROVIDER_ALREADY_LINKED`, ...);
`message` is an English hint for developers.

## Example app

[`example/`](example) is a small app with anonymous sign-in, e-mail sign-up
and sign-in, sign-out and account deletion. It builds the SDK from this
repository:

```bash
cd example
./gradlew :app:installDebug
```

Paste your public key (`rv_live_...`) into its API key field.

## Documentation

- [Rivium Auth Docs](https://rivium.co/cloud/rivium-auth/docs)
- [Android SDK guide](https://rivium.co/cloud/rivium-auth/docs/sdks-android)

## Support

- **Documentation**: [Rivium Auth Docs](https://rivium.co/cloud/rivium-auth/docs)
- **Issues**: [GitHub Issues](https://github.com/Rivium-co/rivium_auth_android_sdk/issues)
- **Email**: support@rivium.co

## License

MIT License — see [LICENSE](LICENSE) for details.
