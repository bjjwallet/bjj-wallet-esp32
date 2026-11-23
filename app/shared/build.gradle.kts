plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
        it.binaries.framework {
            baseName = "AirLockShared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bignum)
            implementation(libs.bitcoin)
            implementation(libs.keccak)
            implementation(libs.kable)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.material3)
            implementation(libs.material.icons)
            implementation(libs.coil.compose)
            implementation(libs.coil.svg)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.core)
            implementation(libs.ktor.negotiation)
            implementation(libs.ktor.json)
            implementation(libs.koin.core)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.ktor.okhttp)
            implementation(libs.secp.android)
            implementation(libs.tink.android)
        }
        androidUnitTest.dependencies { implementation(libs.secp.jvm) }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
    }
}

android {
    namespace = "pub.airlock.shared"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "pub.airlock.resources"
}
