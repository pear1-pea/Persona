import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("androidx.navigation.safeargs.kotlin")
}

android {
    namespace = "com.example.persona"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.persona"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "VOLC_API_KEY", properties.getProperty("VOLC_API_KEY"))
        buildConfigField("String", "VOLC_MODEL_ID", properties.getProperty("VOLC_MODEL_ID"))

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    val nav_version = "2.8.0"
    implementation("androidx.navigation:navigation-fragment-ktx:$nav_version")
    implementation("androidx.navigation:navigation-ui-ktx:$nav_version")

//    implementation("com.google.android.material:material:1.11.0")

    implementation("de.hdodenhof:circleimageview:3.1.0")
    implementation("io.coil-kt:coil:2.6.0")

    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0") //为了 viewModels() 委托
    implementation("androidx.activity:activity-ktx:1.9.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0") // JSON转对象
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0") // 打印日志，方便调试

    // Markdown 渲染
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2") // 删除线
    implementation("io.noties.markwon:ext-tables:4.6.2")        // 表格
    implementation("io.noties.markwon:ext-tasklist:4.6.2")      // 任务列表
    implementation("io.noties.markwon:html:4.6.2")              // HTML 支持
    implementation("io.noties.markwon:image-coil:4.6.2")        // 图片支持 (配合 Coil)

    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version") // 支持协程
    kapt("androidx.room:room-compiler:$room_version")

    val paging_version = "3.3.0" // 使用较新版本
    implementation("androidx.paging:paging-runtime:$paging_version")
    implementation("androidx.room:room-paging:2.6.1")// 编译器

    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation ("com.google.firebase:firebase-crashlytics-ktx")

//    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}