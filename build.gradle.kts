import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `base`
    // Shadow 只在 plugin 模块启用；根声明 apply false，让子模块可以无版本引用。
    id("com.gradleup.shadow") version "9.3.0" apply false
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()

    repositories {
        mavenCentral()
        // com.mojang:brigadier 等 Mojang 库由 Paper 的公共镜像提供（Maven Central 不托管该坐标）。
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        // 目标 MUCore / Folia 26.3 使用 Java 25。
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 默认 512m 的 test worker 会在 IslandFieldTest / BiomeProviderTest 持有多个
        // IslandField（每个平面缓存在 GC 前约 27 MB）时直接崩溃且不产出任何报告。
        // 提高堆上限，而不是缩小被测试的缓存本身。
        maxHeapSize = "2g"
    }

    // MU核心 / Paper API 的传递依赖。
    // mucore-api 的 jar 内嵌了 org.bukkit.* 与 io.papermc.*，但其签名引用了 Adventure /
    // guava / gson 等外部类型，编译期必须可见；版本对齐 MU核心/mucore-api/build.gradle.kts。
    // 全部 compileOnly —— 运行期由服务端提供，绝不随产物打包。
    dependencies {
        add("compileOnly", platform("net.kyori:adventure-bom:5.2.0"))
        add("compileOnly", "net.kyori:adventure-api")
        add("compileOnly", "net.kyori:adventure-key")
        add("compileOnly", "net.kyori:adventure-text-minimessage")
        add("compileOnly", "net.kyori:adventure-text-serializer-gson")
        add("compileOnly", "net.kyori:adventure-text-serializer-legacy")
        add("compileOnly", "net.kyori:adventure-text-serializer-plain")
        add("compileOnly", "net.kyori:adventure-text-logger-slf4j")
        add("compileOnly", "com.google.code.gson:gson:2.14.0")
        add("compileOnly", "com.google.guava:guava:33.6.0-jre")
        add("compileOnly", "it.unimi.dsi:fastutil:8.5.18")
        add("compileOnly", "org.joml:joml:1.10.9")
        add("compileOnly", "org.apache.logging.log4j:log4j-api:2.26.0")
        add("compileOnly", "org.slf4j:slf4j-api:2.0.17")
        add("compileOnly", "com.mojang:brigadier:1.3.11")
        add("compileOnly", "org.jspecify:jspecify:1.0.0")
    }

    // 测试编译 / 运行类路径复用主 compileOnly 的全部依赖（Bukkit & Paper API、Adventure、
    // SnakeYAML 等），否则 javac 解析父类型、或测试加载被测试类时会缺类型。
    configurations.named("testImplementation") {
        extendsFrom(configurations.getByName("compileOnly"))
    }
    configurations.named("testRuntimeOnly") {
        extendsFrom(configurations.getByName("compileOnly"))
    }
}

// `gradlew clean shadowJar` 在根执行时：clean 命中所有子工程，
// shadowJar 命中 :plugin（Gradle 按任务名跨工程匹配），因此无需额外的聚合任务。
