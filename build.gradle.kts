// AGP 9.x has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android anywhere (docs/stack.md D10).
// The compose plugin at the Kotlin version lifts the classpath KGP from AGP's bundled 2.2.10 to 2.4.21.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
