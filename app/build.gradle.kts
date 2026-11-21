plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
android {
    namespace = "com.nyqova.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nyqova.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 15
        versionName = "0.6.1"
        // Android 9 TV boxes commonly have a 32-bit userspace, even on ARM64 CPUs.
        ndk { abiFilters += "armeabi-v7a" }
        testInstrumentationRunner = providers.gradleProperty("testRunner").getOrElse("com.nyqova.app.SmokeInstrumentation")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDirs("../third-party/ape/src/main/java","../third-party/ffmpeg/src/main/java")
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    compileOnly("org.checkerframework:checker-qual:3.33.0")
    implementation(project(":core"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-android:1.7.36")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
