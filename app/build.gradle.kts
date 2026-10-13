plugins { id("com.android.application") }

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "dev.tmn.watchtest"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tmn.watchtest"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}
