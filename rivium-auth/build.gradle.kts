plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "co.rivium.auth"
    compileSdk = 34

    defaultConfig {
        minSdk = 23

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ==================== Test Dependencies ====================
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // The real org.json: the Android one is only a stub on the JVM.
    testImplementation("org.json:json:20231013")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("co.rivium", "rivium-auth", project.findProperty("VERSION_NAME") as String? ?: "0.1.0")

    pom {
        name.set("RiviumAuth Android SDK")
        description.set("Sign-in for Android apps: anonymous, Google, Apple, e-mail, magic link and Telegram")
        inceptionYear.set("2026")
        url.set("https://github.com/Rivium-co/rivium_auth_android_sdk")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("rivium")
                name.set("Rivium")
                email.set("founder@rivium.co")
                url.set("https://rivium.co")
            }
        }

        scm {
            url.set("https://github.com/Rivium-co/rivium_auth_android_sdk")
            connection.set("scm:git:git://github.com/Rivium-co/rivium_auth_android_sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rivium-co/rivium_auth_android_sdk.git")
        }
    }
}
