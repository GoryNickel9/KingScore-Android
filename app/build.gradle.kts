import java.util.Properties
import java.util.Base64
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val supabaseProperties = Properties().apply {
    rootProject.file("supabase.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun config(name: String): String = System.getenv(name) ?: supabaseProperties.getProperty(name, "")
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val publicKey = config("SUPABASE_PUBLIC_KEY")
val supabaseUrl = config("SUPABASE_URL")
val apkVersionCode = providers.gradleProperty("kingScoreVersionCode").orElse("19").get().toInt()
val apkVersionName = providers.gradleProperty("kingScoreVersionName").orElse("0.6.1").get()
require(apkVersionCode > 0 && apkVersionName.matches(Regex("[0-9A-Za-z.+_-]{1,64}"))) { "Versione APK non valida" }
if (publicKey.isNotBlank()) {
    val legacyPayload = runCatching {
        Base64.getUrlDecoder().decode(publicKey.split('.')[1]).toString(Charsets.UTF_8)
    }.getOrDefault("")
    require(publicKey.startsWith("sb_publishable_") || Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(legacyPayload)) {
        "SUPABASE_PUBLIC_KEY deve essere una chiave pubblica publishable/anon, mai service_role."
    }
}

android {
    namespace = "app.kingscore.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.kingscore.android"
        minSdk = 26
        targetSdk = 36
        versionCode = apkVersionCode
        versionName = apkVersionName
        testInstrumentationRunner = "app.kingscore.android.data.local.PendingRecoveryProbe"
        buildConfigField("String", "SUPABASE_URL", quoted(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", quoted(publicKey))
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("../contracts/v1")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val endpoint = runCatching { URI(supabaseUrl) }.getOrNull()
        require(endpoint != null && endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null &&
            endpoint.query == null && endpoint.fragment == null && endpoint.path in listOf("", "/") && publicKey.isNotBlank()) {
            "La release richiede URL Supabase HTTPS e chiave pubblica configurati localmente"
        }
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.mock)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.test.manifest)
}
