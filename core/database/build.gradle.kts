plugins {
    alias(libs.plugins.bika.android.library)
    alias(libs.plugins.bika.android.room)
    alias(libs.plugins.bika.hilt)
}

android {
    namespace = "com.shizq.bika.core.database"

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.core.model)

    implementation(libs.kotlinx.datetime)

    // DownloadTaskDaoTest 在 src/test（本地单测）里用 runTest，依赖必须挂在 test 上。
    // 原先挂在 androidTest 上，而本模块根本没有 androidTest 源集，
    // 结果是单测编译期直接 Unresolved reference 'runTest'。
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}