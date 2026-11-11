import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.play.publisher)
  jacoco
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { load(it) }
  }
}

base.archivesName.set("Corewatch")

android {
  namespace = "com.droid.corewatch"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.droid.corewatch"
    minSdk = 26
    targetSdk = 36
    versionCode = 4
    versionName = "1.1.2"
    // Universal APK bundling 16KB-aligned x86_64 and arm64-v8a JNI bridges with pure-Kotlin fallbacks.
  }

  signingConfigs {
    create("upload") {
      storeFile = file(
        System.getenv("ANDROID_UPLOAD_KEYSTORE_PATH")
          ?: keystoreProps.getProperty("storeFile")
          ?: "upload-key.jks"
      )
      storePassword = System.getenv("ANDROID_UPLOAD_KEYSTORE_PASSWORD")
        ?: keystoreProps.getProperty("storePassword")
      keyAlias = System.getenv("ANDROID_UPLOAD_KEY_ALIAS")
        ?: keystoreProps.getProperty("keyAlias")
      keyPassword = System.getenv("ANDROID_UPLOAD_KEY_PASSWORD")
        ?: keystoreProps.getProperty("keyPassword")
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    debug {
      enableUnitTestCoverage = true
    }
    release {
      signingConfig = signingConfigs.getByName("upload")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
      }
    }
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,INDEX.LIST,LICENSE*,NOTICE*}"
      excludes += "META-INF/versions/**"
      excludes += "META-INF/*.kotlin_module"
    }
  }
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

play {
  // Path to the Google Play Service Account JSON key file (relative to app/ directory)
  serviceAccountCredentials.set(
    file(System.getenv("ANDROID_PLAY_CREDENTIALS_PATH") ?: "play-store-keys.json")
  )
  track.set(System.getenv("ANDROID_PLAY_TRACK") ?: "internal")
  defaultToAppBundles.set(true)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(composeBom)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core.ktx)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
