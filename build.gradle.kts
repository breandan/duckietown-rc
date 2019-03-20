buildscript {
    val kotlinVersion by extra { "1.3.21" }

    apply(from = "https://github.com/rosjava/android_core/raw/kinetic/buildscript.gradle")
    repositories {
        google()
        jcenter()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:3.5.0-alpha07")
        classpath(kotlin("gradle-plugin", version = kotlinVersion))
    }
}

apply {
    plugin("catkin")
}

val kotlinVersion: String by extra

allprojects {
    group = "com.github.rosjava.android_apps"
    version = "0.1"
    extra.apply{
        set("kotlinVersion", kotlinVersion)
//        set("targetSdkVersion", 27)
    }
}

subprojects {
    apply { plugin("ros-android") }
}