import de.undercouch.gradle.tasks.download.Download

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
    id("de.undercouch.download")
}

android {
    compileSdkVersion(28)
    buildToolsVersion("28.0.3")

    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(28)
        versionCode = 2
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    aaptOptions {
        noCompress("tflite")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}


val ASSET_DIR = "$projectDir/src/main/assets"
val TMP_DIR   = "${project.buildDir}/downloads"

tasks {
    val destination = File(buildDir, "zips/")

    val downloadZipFile by creating(Download::class) {
        src("http://storage.googleapis.com/download.tensorflow.org/models/tflite/coco_ssd_mobilenet_v1_1.0_quant_2018_06_29.zip")
        dest(destination)
        overwrite(true)
    }

    val downloadAndUnzipFile by creating(Copy::class) {
        dependsOn(downloadZipFile)
        from(zipTree(destination))
        into(ASSET_DIR)
    }

    val extractModels by creating(Copy::class) {
        dependsOn(downloadAndUnzipFile)
    }

    whenTaskAdded {
        if (name in listOf("assembleDebug", "assembleRelease"))
            dependsOn(extractModels)
    }
}

val kotlinVersion = ext.get("kotlinVersion") as String

dependencies {
    implementation("com.github.rosjava.android_remocons:common_tools:[0.3,0.4)")
    implementation("org.ros.android_core:android_core_components:[0.4,0.5)")
    implementation("com.android.support:appcompat-v7:28.0.0")
    implementation("org.tensorflow:tensorflow-lite:0.0.0-nightly")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
    testImplementation("junit:junit:4.12")
    androidTestImplementation("androidx.test.ext:junit:1.1.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1")
}