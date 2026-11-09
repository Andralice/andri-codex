plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("ANDRI_RELEASE_STORE_FILE").orNull
    ?: providers.environmentVariable("ANDRI_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("ANDRI_RELEASE_STORE_PASSWORD").orNull
    ?: providers.environmentVariable("ANDRI_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("ANDRI_RELEASE_KEY_ALIAS").orNull
    ?: providers.environmentVariable("ANDRI_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("ANDRI_RELEASE_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("ANDRI_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigning) {
    throw GradleException("Release signing is partially configured. Set all ANDRI_RELEASE_* values or leave them all unset.")
}

android {
    namespace = "com.codexserver.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.codexserver.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 47
        versionName = "0.1.46"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (hasCompleteReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    packaging {
        resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        resources.excludes += "META-INF/OSGI-INF/MANIFEST.MF"
        resources.excludes += "META-INF/LICENSE*"
        resources.excludes += "META-INF/NOTICE*"
    }
    sourceSets.getByName("test").java.srcDir("../codex/checks")
}
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
    maxHeapSize = "256m"
    maxParallelForks = 1
}
tasks.register("exportCheckClasspath") {
    dependsOn("compileDebugUnitTestKotlin", "bundleDebugClassesToRuntimeJar")
    doLast {
        val task = tasks.named<Test>("testDebugUnitTest").get()
        rootProject.file("codex/check-classpath.txt").writeText(task.classpath.asPath)
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    // Android has no Java 15 EdDSA implementation; JSch needs these lightweight crypto primitives.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
