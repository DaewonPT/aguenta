import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val versao = "0.1.2"

// Chave de lançamento: no CI vem dos segredos do GitHub; no PC vem de ../Chave Privada/keystore.properties (fora do repositório).
val propsChave = rootProject.file("../Chave Privada/keystore.properties")
val chaveLocal = Properties().apply { if (propsChave.isFile) propsChave.inputStream().use(::load) }
fun valorChave(env: String, prop: String): String? = System.getenv(env) ?: chaveLocal.getProperty(prop)

android {
    namespace = "pt.pixellusitano.aguenta"
    compileSdk = 37

    defaultConfig {
        applicationId = "pt.pixellusitano.aguenta"
        minSdk = 26
        targetSdk = 36
        versionName = versao
        // Mesma regra que versionCodeFromTag em Updates.kt: major*10000 + minor*100 + patch.
        versionCode = versao.split('.').map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    signingConfigs {
        create("release") {
            val caminho = System.getenv("ANDROID_KEYSTORE_FILE")
                ?: chaveLocal.getProperty("storeFile")?.let { propsChave.resolveSibling(it).path }
            if (caminho != null) {
                storeFile = file(caminho)
                storePassword = valorChave("ANDROID_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = valorChave("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = storePassword // PKCS12: uma só palavra-passe
            }
        }
    }

    buildTypes {
        release {
            // Sem chave (fork, PC novo) compila na mesma, mas sai app-release-unsigned.apk, que não atualiza a instalada.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json) // o org.json do android.jar só tem stubs nos testes da JVM
}
