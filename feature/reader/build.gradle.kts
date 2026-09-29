plugins {
    alias(libs.plugins.bika.android.feature.impl)
    alias(libs.plugins.bika.android.library.compose)
}

android {
    namespace = "com.shizq.bika.feature.reader.impl"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.download)
    // 依赖注入连接预热：进阅读会话前先把到图片源的连接摊好
    implementation(projects.core.network)

    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.flowredux)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.telephoto)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}