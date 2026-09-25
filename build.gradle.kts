plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    dependencies {
        // AGP 9 memakai Kotlin bawaan; versinya ditentukan oleh KGP di classpath.
        classpath(libs.kotlin.gradle.plugin)
    }
}
