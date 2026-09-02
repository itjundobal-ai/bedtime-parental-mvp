plugins { id("com.android.application") }

android {
    namespace = "com.master.bedtime.parent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.master.bedtime.child"
        minSdk = 26
        targetSdk = 36
        versionCode = 2000
        versionName = "1.0.0-universal"
    }
}
