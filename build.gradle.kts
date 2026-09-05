plugins {
    alias(libs.plugins.agp) apply false
    alias(libs.plugins.kotlin) apply false
}

buildscript {
    extra.apply{
        set("kotlin_version", "1.9.23")
        set("java_version", JavaVersion.VERSION_17)
    }
    repositories {
        google()
        mavenCentral()
        maven ( url = "https://jitpack.io" )
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven ( url = "https://jitpack.io" )
    }
}

tasks.register<Delete>("clean") {
    delete(fileTree(rootProject.layout.buildDirectory))
}
