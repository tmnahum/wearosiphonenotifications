plugins { id("com.android.application") }

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
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
