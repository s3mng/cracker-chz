plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Map<String, String> =
    if (keystorePropsFile.exists()) {
        keystorePropsFile.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || "=" !in trimmed) {
                null
            } else {
                val index = trimmed.indexOf("=")
                trimmed.substring(0, index) to trimmed.substring(index + 1)
            }
        }.toMap()
    } else {
        emptyMap()
    }
val hasReleaseSigning =
    System.getenv("SIGNING_STORE_FILE") != null || keystoreProps["storeFile"] != null

android {
    namespace = "app.cracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.cracker"
        minSdk = 35
        targetSdk = 37
        versionCode = 9
        versionName = "2.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }
                    ?: rootProject.file(keystoreProps.getValue("storeFile"))
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    ?: keystoreProps.getValue("storePassword")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    ?: keystoreProps.getValue("keyAlias")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: keystoreProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
