plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.argsecure.rastagmobility"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.argsecure.rastagmobility"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "2.0.0-motion"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":mobility-api"))
    implementation(libs.core)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
