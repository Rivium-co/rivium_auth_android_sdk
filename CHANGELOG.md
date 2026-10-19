# Changelog

## 0.1.1

- Sends `X-Rivium-SDK: auth-android/0.1.1` with every request so Rivium can show
  which SDK versions are in use. No behaviour change.

## 0.1.0

- First release.
- Sign-in methods: anonymous, Google, Apple, e-mail and password, magic link
  and Telegram code.
- Upgrade an anonymous user with `linkGoogle`, `linkApple` and
  `linkPassword`; the user keeps its id.
- `RiviumNonce` for Google and Apple ID tokens; `rawNonce` is required for
  Google and Apple sign-in and linking.
- The refresh token is stored encrypted with an Android Keystore key; the
  access token stays in memory and is refreshed before it expires, one
  refresh at a time.
- `tokenProvider`, `strictTokenProvider` and `blockingTokenProvider` plug
  straight into the other Rivium SDKs.
- `signOut()`, `signOutEverywhere()`, `deleteAccount()`, `authState` and auth
  state listeners.
