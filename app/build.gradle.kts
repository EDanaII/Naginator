import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.work.DisableCachingByDefault
import java.io.File

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

@DisableCachingByDefault(because = "Produces a companion APK in the existing packaging output directory")
abstract class CopyVersionedApk : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val artifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val buildType: Property<String>

    @TaskAction
    fun copyApks() {
        val artifacts = checkNotNull(artifactsLoader.get().load(apkDirectory.get())) {
            "APK output metadata is missing"
        }
        val copies = artifacts.elements.map { artifact ->
            val versionName = checkNotNull(artifact.versionName)
            require(versionName.matches(Regex("[A-Za-z0-9._+-]+"))) {
                "Version name must be filename-safe for the companion APK"
            }
            val source = File(artifact.outputFile)
            source to File(source.parentFile,
                "Naginator-$versionName-b${artifact.versionCode}-${buildType.get()}.apk")
        }
        check(copies.map { it.second }.distinct().size == copies.size) {
            "APK splits need distinct version codes to produce unique companion filenames"
        }
        copies.forEach { (source, destination) ->
            source.copyTo(destination, overwrite = true)
            logger.lifecycle("Versioned APK: ${destination.absolutePath}")
        }
    }
}

val distributedVersionCode = providers.gradleProperty("naginatorVersionCode").get().toInt()
require(distributedVersionCode in 1..2_100_000_000) { "naginatorVersionCode must be a positive Android version code" }
val sourceRevision = providers.gradleProperty("naginatorGitRevision").orElse(providers.exec {
    commandLine("git", "describe", "--always", "--dirty", "--abbrev=8", "--exclude=*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }).get()
require(sourceRevision.matches(Regex("(?:[0-9a-f]{7,40}(?:-dirty)?|unknown)"))) {
    "naginatorGitRevision must be an abbreviated commit, optionally followed by -dirty"
}

android {
    namespace = "com.EDanaII.pillreminder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.EDanaII.pillreminder"
        minSdk = 26
        targetSdk = 37
        versionCode = distributedVersionCode
        versionName = providers.gradleProperty("naginatorVersionName").get()
        buildConfigField("String", "GIT_REVISION", "\"$sourceRevision\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

androidComponents.onVariants { variant ->
    val copyApk = tasks.register<CopyVersionedApk>("copy${variant.name.replaceFirstChar { it.uppercase() }}VersionedApk") {
        artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        buildType.set(requireNotNull(variant.buildType))
    }
    variant.artifacts.use(copyApk).wiredWith { it.apkDirectory }.toListenTo(SingleArtifact.APK)
}

dependencies {
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
