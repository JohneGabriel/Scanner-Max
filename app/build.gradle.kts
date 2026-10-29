plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.obd.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.obd.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "1.8"
    }

    signingConfigs {
        getByName("debug") {
            // chave fixa no repo: o APK de cada build tem a mesma assinatura,
            // então dá para atualizar o app sem desinstalar
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // chave definitiva (só existe no CI, vinda dos segredos do GitHub)
        val ks = System.getenv("SMX_KEYSTORE")
        if (!ks.isNullOrBlank() && file(ks).exists()) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("SMX_KEYSTORE_PASS")
            keyAlias = "scannermax"
            keyPassword = System.getenv("SMX_KEYSTORE_PASS")
        }
    }

    buildTypes {
        release {
            // R8 remove os ícones e o código que não usamos (o APK fica pequeno)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // chave definitiva quando disponível; senão a de testes (builds locais)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
