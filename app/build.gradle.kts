import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  jacoco
}

base.archivesName.set("Corewatch")

android {
  namespace = "com.droid.corewatch"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.droid.corewatch"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
    // Pure Kotlin/JVM app: no native libs, so the single APK runs on x86_64 and arm64-v8a.
  }

  buildTypes {
    debug {
      enableUnitTestCoverage = true
    }
    release {
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
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(composeBom)
  testImplementation("org.robolectric:robolectric:4.16")
  testImplementation("androidx.test:core-ktx:1.7.0")
  testImplementation("androidx.test.ext:junit:1.3.0")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
