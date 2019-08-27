buildscript {
    val kotlinVersion by extra { "1.3.31" }

    apply(from = "https://github.com/rosjava/android_core/raw/kinetic/buildscript.gradle")
    repositories {
        google()
        jcenter()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:3.6.0-alpha07")
        classpath("de.undercouch:gradle-download-task:3.4.3")

        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

apply {
    plugin("catkin")
}

val kotlinVersion: String by extra

allprojects {
    repositories {
        google()
        jcenter()
	//maven("https://raw.githubusercontent.com/breandan/kotlingrad/master/releases")
    }

    group = "com.github.rosjava.android_apps"
    version = "0.1"
    extra.apply {
        set("kotlinVersion", kotlinVersion)
//        set("targetSdkVersion", 27)
    }
}

subprojects {
    apply {
        plugin("ros-android")
        plugin("de.undercouch.download")
    }
}