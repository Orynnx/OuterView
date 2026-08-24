import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    compileSdk = 36
    namespace = "org.orynnx.outerview.core"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        minSdk = 36
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "org.orynnx.outerview"
                artifactId = "fun-card-core"
                version = "3.0.0"
                from(components["release"])
            }
        }
    }
}
