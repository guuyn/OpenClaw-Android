pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Aliyun mirrors as fallback (for China mainland)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Aliyun mirrors as fallback (for China mainland)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Bugly NDK native crash reporting
        maven { url = uri("https://android.bugly.qq.com/maven") }
    }
}

rootProject.name = "OpenClaw"
include(":app")
include(":android_compose")
include(":script")