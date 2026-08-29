// Root build file. Plugins are declared here with `apply false` so the version
// catalog resolves them once for the whole build; the :app module then applies
// them without repeating a version.
//
// Note the absence of `org.jetbrains.kotlin.android`. From AGP 9.0 the Android
// plugin has built-in Kotlin support and applies the Kotlin plugin itself;
// applying it explicitly is now a hard error, not a warning.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
}
