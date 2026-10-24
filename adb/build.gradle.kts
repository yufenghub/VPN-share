plugins { id("com.android.library") }
android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    testOptions.unitTests.all {
        System.getenv("SPAKE2_TEST_LIB_DIR")?.let { path -> it.jvmArgs("-Djava.library.path=$path") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { textReport = true; textOutput = file("stdout"); htmlReport = false; xmlReport = false }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("org.bouncycastle:bcprov-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
}
