pluginManagement {
    repositories {
        // 本机直连 dl.google.com 可用，但 plugins.gradle.org / services.gradle.org 被墙，
        // 统一走阿里云镜像；末尾保留官方源作为兜底。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "lynk_cluster"
include(":app")
