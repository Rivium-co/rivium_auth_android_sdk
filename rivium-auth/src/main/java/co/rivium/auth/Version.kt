package co.rivium.auth

/**
 * The SDK version. Keep it equal to VERSION_NAME in gradle.properties (and the fallback in
 * rivium-auth/build.gradle.kts) — a unit test checks it.
 */
internal const val SDK_VERSION = "0.1.1"

/** Sent as `X-Rivium-SDK` with every request, so Rivium can show which SDK versions are in use. */
internal const val SDK_HEADER = "auth-android/$SDK_VERSION"
