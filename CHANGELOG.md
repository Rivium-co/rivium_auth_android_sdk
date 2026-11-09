# Changelog

## 0.2.0

- `getTokenResult(forceRefresh)`: the access token with its decoded fields, like Firebase's `GetTokenResult`.
  `claims` holds the custom claims your server set with `setCustomUserClaims` (for example `plan`, `role`), plus
  `sub`, `iat`, `exp`, `auth_time` and `amr`; also `expirationTimestamp`, `issuedAtTimestamp`, `authTimestamp` and
  `signInProvider`. For showing things in the app; access is still decided on the server. Pass `forceRefresh = true`
  right after your server changed the claims.

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
