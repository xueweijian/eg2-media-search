plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xueweijian.eg2media"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xueweijian.eg2media"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // 首发只发 arm64（HANDOFF §0 兼容性下限）
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        // Kotlin 2.4：kotlinOptions DSL 已移除，改用 compilerOptions
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 检索栈（HANDOFF §3）：MediaPipe tasks-retrieval 1.1.0（传递依赖 LiteRT-LM）
    implementation("com.google.mediapipe:tasks-retrieval:1.1.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // 后台索引（HANDOFF §3/§4：充电+空闲约束、断点续跑）
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // 文档模态：ML Kit 中文 OCR（bundled 离线，国内无 GMS 也可用）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // tasks-retrieval 1.1.0 的字节码引用 litertlm 0.18.x 的类（如 ActivationDataType），
    // 其 POM 却声明 0.17.0-alpha1（无该类，运行时 NoClassDefFoundError）。显式 pin 0.18.0。
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // 纯 JVM 单测（core 包第一性原子层，TDD）
    testImplementation("junit:junit:4.13.2")
}
