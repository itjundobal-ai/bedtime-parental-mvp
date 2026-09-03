plugins { id("com.android.library") }

android {
    namespace = "com.master.bedtime.child"
    compileSdk = 36

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
