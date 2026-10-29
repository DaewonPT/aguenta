// Todos os plugins declarados aqui com apply false: assim o Kotlin 2.4.21 substitui o que o AGP 9 traz.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
