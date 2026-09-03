plugins { id("com.android.application") }

android {
    namespace = "com.master.bedtime.parent"
    compileSdk = 36

    val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toIntOrNull() ?: 1
    val signingStorePassword = System.getenv("BEDTIME_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("BEDTIME_KEY_ALIAS")
    val signingKeyPassword = System.getenv("BEDTIME_KEY_PASSWORD")

    defaultConfig {
        applicationId = "com.master.bedtime.parent"
        minSdk = 26
        targetSdk = 36
        versionCode = 3000 + buildNumber
        versionName = "1.0.$buildNumber-parent"
    }

    signingConfigs {
        create("release") {
            if (!signingStorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()) {
                storeFile = rootProject.file("bedtime-release.jks")
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
}
