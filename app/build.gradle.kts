import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun config(name: String): String = providers.environmentVariable(name).orNull
    ?: local.getProperty(name, "")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun boolConfig(name: String): String = (config(name).equals("true", ignoreCase = true)).toString()

android {
    namespace = "com.nfcwriter.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nfcwriter.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", quoted(config("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted(config("SUPABASE_ANON_KEY")))
        buildConfigField("String", "TAPTAG_SITE_URL", quoted(config("TAPTAG_SITE_URL").ifBlank { "https://taptagdirect.tech" }))
        buildConfigField("boolean", "MOCK_SYNC_ENABLED", boolConfig("MOCK_SYNC_ENABLED"))
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

    signingConfigs {
        create("release") {
            val path = config("RELEASE_STORE_FILE")
            if (path.isNotBlank()) {
                storeFile = file(path)
                storePassword = config("RELEASE_STORE_PASSWORD")
                keyAlias = config("RELEASE_KEY_ALIAS")
                keyPassword = config("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (config("RELEASE_STORE_FILE").isNotBlank()) signingConfigs.getByName("release") else null
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation(platform("io.github.jan-tennert.supabase:bom:3.7.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.core:core:1.17.0", "androidx.core:core-ktx:1.17.0",
        "androidx.activity:activity:1.11.0", "androidx.activity:activity-ktx:1.11.0", "androidx.activity:activity-compose:1.11.0",
        "androidx.lifecycle:lifecycle-runtime:2.9.4", "androidx.lifecycle:lifecycle-runtime-ktx:2.9.4",
        "androidx.lifecycle:lifecycle-runtime-compose:2.9.4", "androidx.lifecycle:lifecycle-viewmodel:2.9.4", "androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4",
        "androidx.browser:browser:1.9.0"
        ,"org.jetbrains.kotlin:kotlin-stdlib:2.3.20", "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.20", "org.jetbrains.kotlin:kotlin-reflect:2.3.20"
    )
}

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}
