import com.android.build.api.dsl.LibraryExtension
import com.shizq.bika.configureKotlinAndroid
import com.shizq.bika.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = 37
                lint.targetSdk = 37
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                testOptions.animationsDisabled = true
                // The resource prefix is derived from the module name,
                // so resources inside ":core:module1" must be prefixed with "core_module1_"
                resourcePrefix =
                    path.split("""\W""".toRegex()).drop(1).distinct().joinToString(separator = "_")
                        .lowercase() + "_"
            }
            dependencies {
                "androidTestImplementation"(libs.findLibrary("kotlin.test").get())
                "testImplementation"(libs.findLibrary("kotlin.test").get())
                // kotlin-test 单独一个包不提供 kotlin.test.* 的 JUnit4 实现，
                // 少了这一行各模块写 `import kotlin.test.Test` 会 Unresolved reference
                "testImplementation"(libs.findLibrary("kotlin.test.junit").get())
                "testImplementation"(libs.findLibrary("junit").get())
                // kotlin-logging 在类初始化时解析 org.slf4j.LoggerFactory，而 slf4j 的具体
                // 绑定只声明在 core:logging 且为 implementation。少了这一行，任何在
                // 静态初始化里建 logger 的类一旦被单测触到就抛 NoClassDefFoundError。
                "testImplementation"(libs.findLibrary("slf4j.api").get())

                "implementation"(libs.findLibrary("androidx.tracing.ktx").get())

                "implementation"(libs.findLibrary("kotlin.logging").get())
            }
        }
    }
}