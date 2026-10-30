pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 允许 Gradle 自动定位 / 供给 JDK 25（本机 JDK 25 由 Gradle 托管在 ~/.gradle/jdks）。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "betterend-bukkit"

// 本工程为 common（与平台无关的地形 / 群系逻辑）+ plugin（Paper/Folia 入口与资源）两部分。
// nms 层不再需要：BetterEnd 只用 Paper 原生 bootstrap / 生成器 API。
include(":common", ":plugin")
