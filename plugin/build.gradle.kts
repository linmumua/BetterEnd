import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("com.gradleup.shadow")
    `maven-publish`
}

// 依赖的实际 jar（仅编译期；运行期由服务端 / CraftEngine 插件提供）。
val mucoreApi = rootProject.file(providers.gradleProperty("mucoreApiJar").get())
val craftEngine = rootProject.file(providers.gradleProperty("craftEngineJar").get())
val snakeyamlVersion = providers.gradleProperty("snakeyamlVersion").get()
val pythonExecutable = providers.gradleProperty("pythonExecutable").get()

// DnT 原 ZIP（仓库根，保持只读）与集成脚本（由其它 agent 负责，本工程只调用、不修改）。
val dntZip = rootProject.file(providers.gradleProperty("dntZip").get())
val dntScript = rootProject.file("scripts/prepare_dnt.py")
// 生成目录的内容与 jar 内路径一一对应：
//   datapacks/dnt-end-castle/**、craftengine/dnt_end_castle/**。
val dntGeneratedDir = layout.buildDirectory.dir("generated/dnt")

dependencies {
    // 运行期随产物打包的自家模块。
    implementation(project(":common"))
    // 仅编译期：Paper/Bukkit API 与 CraftEngine 由服务端提供，绝不打进产物。
    compileOnly(files(mucoreApi))
    compileOnly(files(craftEngine))
    compileOnly("org.jetbrains:annotations:26.1.0")
    // 随产物打包并重定位（见下方 shadowJar）；不依赖服务端内置的 org.yaml。
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// ---------------------------------------------------------------------------
// DnT 资源迁移：调用只读脚本，从原 ZIP 生成适配后的数据包与 CraftEngine 资源。
// 产物约定：
//   <out>/datapacks/dnt-end-castle/{pack.mcmeta,data/...}
//   <out>/craftengine/dnt_end_castle/{pack.yml,resourcepack/assets/...}
// ---------------------------------------------------------------------------
val prepareDntResources = tasks.register<Exec>("prepareDntResources") {
    group = "build"
    description = "调用 scripts/prepare_dnt.py，从 DnT 原 ZIP 生成适配后的数据包与 CE 资源。"

    // optional()：脚本或 ZIP 缺失时不在输入校验阶段抛出，交由 doFirst 给出明确中文错误。
    inputs.files(dntZip).optional().withPropertyName("dntZip")
    inputs.files(dntScript).optional().withPropertyName("dntScript")
    // 迁移规则已拆到 scripts/dntlib/*.py：纳入输入后，规则变化能正确失效，避免 UP-TO-DATE 漏跑。
    inputs.dir(rootProject.file("scripts/dntlib")).withPropertyName("dntLibDir")
    outputs.dir(dntGeneratedDir)

    doFirst {
        check(dntZip.isFile) { "缺少 DnT 原 ZIP：${dntZip.absolutePath}" }
        check(dntScript.isFile) { "缺少生成脚本：${dntScript.absolutePath}（应由集成脚本提供）" }
    }

    commandLine(
        pythonExecutable,
        dntScript.absolutePath,
        "--input", dntZip.absolutePath,
        "--output", dntGeneratedDir.get().asFile.absolutePath
    )
}

// 生成目录内容直接并入主资源：其内部已带 datapacks/ 与 craftengine/ 前缀，落点即 jar 内路径。
tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareDntResources)
    from(dntGeneratedDir)
    // 许可证以仓库根文件为准（见 shadowJar）；资源目录内的旧副本不参与打包，避免同名冲突。
    exclude("LICENSE", "LICENSE.ASSETS", "THIRD-PARTY-NOTICES")
    // 仅两个描述符做版本展开：CE 配置里满是 ${...} 占位符，不能被模板引擎处理。
    inputs.property("version", project.version.toString())
    filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
        expand("version" to project.version.toString())
    }
}

// DnT 资源契约测试：把生成目录并入测试资源，测试可直接以 classpath 读取
// （如 datapacks/dnt-end-castle/pack.mcmeta、craftengine/dnt_end_castle/pack.yml）。
tasks.named<ProcessResources>("processTestResources") {
    from(dntGeneratedDir)
    dependsOn(prepareDntResources)
}

// 禁用普通（thin）jar：部署只允许 Shadow 产物。
// shadowJar 以 sourceSets.main.output 为基座，不依赖本任务，故禁用后仍能正常打包；
// :common:jar 不受影响，继续供 shadow 解析工程依赖。
// 若保留本任务，它会产出与 shadowJar 同名同版本的瘦包（仅一个在 build/libs、
// 一个在 jars/），极易被误部署——正好复现用户 main 存在、bootstrapper 缺失的
// ClassNotFoundException。仅加 -plain 分类器不足以消除该误用面，故直接停用。
tasks.named<Jar>("jar") {
    enabled = false
}

// ---------------------------------------------------------------------------
// 最终产物：jars/BetterEnd-<version>.jar
// 只叠加自家代码与运行期库（SnakeYAML 被重定位）；CE / API / UAA 一律排除。
// ---------------------------------------------------------------------------
val shadowJarTask = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("BetterEnd")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("jars"))

    manifest {
        attributes(
            "Implementation-Title" to "BetterEnd",
            "Implementation-Version" to project.version.toString()
        )
    }

    // 只打包运行期类路径；compileOnly 的服务端 API / CraftEngine 不在其中。
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // 重定位 SnakeYAML，避免与服务端内置 org.yaml 及其它插件冲突。
    relocate("org.yaml.snakeyaml", "dev.mumu.betterend.libs.yaml")

    // 防回退护栏：即便那些类误入类路径，也绝不打进产物。
    // org/bukkit/** 已覆盖 org.bukkit.craftbukkit.**，无需再列 craftbukkit。
    exclude("org/bukkit/**", "org/spigotmc/**")
    exclude("io/papermc/**")
    // 服务端内部实现（NMS）永不入包：产物只引用服务端已加载的 NMS 类型。
    // 自家桥接类在 dev/mumu/betterend/vanilla/ 下，不匹配本模式，不会被误排
    // （tools/verify_jar.py 的 NMS 门禁会核对它确实还在产物里）。
    exclude("net/minecraft/**")
    exclude("net/momirealms/**", "org/momirealms/**")
    exclude("com/fren_gor/**", "net/kyori/**")

    // 仓库根许可证与第三方声明。
    from(rootProject.file("LICENSE"))
    from(rootProject.file("LICENSE.ASSETS"))
    from(rootProject.file("THIRD-PARTY-NOTICES"))
    // 打包库自身的许可证放到独立路径，避免与根 LICENSE 同名互相覆盖。
    into("META-INF/licenses") {
        from(rootProject.file("tools/licenses/snakeyaml-LICENSE.txt")) {
            rename { "snakeyaml-LICENSE.txt" }
        }
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------
// 产物校验：内容 / 重定位 / 描述符版本 / 许可证。由 tools/verify_jar.py 执行。
// verifyJar 位于构建与发布的关键路径上：assemble / build / PublishToMavenRepository
// 均依赖它（见下方 dependsOn），因此任何一次构建或发布都必须先通过产物校验，
// 而非仅在手动 `gradlew verifyJar` 时触发。它只依赖 shadowJar，不反向依赖构建，无循环。
// ---------------------------------------------------------------------------
val verifyScript = rootProject.file("tools/verify_jar.py")
val shadowJarFile = shadowJarTask.flatMap { it.archiveFile }
tasks.register<Exec>("verifyJar") {
    group = "verification"
    description = "校验 Shadow 产物：数据包 / CE 资源 / SnakeYAML 重定位 / 描述符版本 / 许可证。"
    dependsOn(shadowJarTask)
    inputs.file(verifyScript)
    inputs.file(shadowJarFile)
    doFirst {
        commandLine(
            pythonExecutable,
            verifyScript.absolutePath,
            shadowJarFile.get().asFile.absolutePath
        )
    }
}

// ---------------------------------------------------------------------------
// Echoes 真实 codec 验收：对 Shadow 产物里的 echoes 数据包跑 26.3 的真实 Codec。
// tools/verify_echoes_codecs.py 复用 verify_dnt_codecs.py 的无头探针机制
// （Bootstrap.bootStrap + JsonOps，不启服、不建世界），逐项解码
// template_pool 的 element/processors/blocks、structure 的 start_height、
// structure_set 的 placement。
//
// 0.1.6 的事故正是这一层缺失造成的：Java 契约测试只验文件齐全/引用闭合，从不按 codec 解码；
// 而唯一的真实 codec 门禁 verify_dnt_codecs.py 只覆盖 DnT 包，DnT 全包没有 block_ignore，
// 所以 BlockState.CODEC 这条路径从未被走过。结果 template_pool 里 {"Name": ...} 的旧式写法
// 一路进了发布产物，注册表 freeze 时 Unbound -> 服务器启动中止。
//
// 只依赖 shadowJar、不反向依赖构建，无循环；由 verifyJar 依赖它，于是
// assemble / build / PublishToMavenRepository 既有的 dependsOn(verifyJar) 都会先过这道门。
// 显式传 --jar：验的是真正要发布的字节，不是源码树（源码树的同构断言在
// EchoesPackContractTest 里，两者互补而非重复）。
// ---------------------------------------------------------------------------
val echoesCodecScript = rootProject.file("tools/verify_echoes_codecs.py")
// 门禁实际依赖的三份输入：它自己、被复用的 DnT 管道、以及编译出的 Java 探针。
val echoesSharedCodecScript = rootProject.file("tools/verify_dnt_codecs.py")
val echoesCodecProbe = rootProject.file("tools/codec/DntCodecProbe.java")
tasks.register<Exec>("verifyEchoesCodecs") {
    group = "verification"
    description = "对 Shadow 产物里的 Echoes 数据包跑 26.3 真实 Codec 验收（template_pool / structure / structure_set）。"
    dependsOn(shadowJarTask)
    inputs.file(echoesCodecScript)
    inputs.file(echoesSharedCodecScript)
    inputs.file(echoesCodecProbe)
    inputs.file(shadowJarFile)
    doFirst {
        // 缺任何一份输入就让任务失败，绝不静默跳过：这三份文件是本门禁的全部依据，
        // 少一份就等于「没验」，而不是「验过且通过」。
        listOf(echoesCodecScript, echoesSharedCodecScript, echoesCodecProbe).forEach { required ->
            check(required.isFile) {
                "缺少 Echoes codec 门禁依赖：${required.absolutePath}"
            }
        }
        commandLine(
            pythonExecutable,
            echoesCodecScript.absolutePath,
            "--jar", shadowJarFile.get().asFile.absolutePath
        )
    }
}

// 两个构建入口共用结构 ID 规则，先校验 public 定义与 legacy 只读别名，避免重复放置。
val structureIdScript = rootProject.file("scripts/dntlib/structure_ids.py")
val echoesPackRoot = rootProject.file("plugin/src/main/resources/datapacks/echoes-end-structures")
val verifyEchoesStructureLayout = tasks.register<Exec>("verifyEchoesStructureLayout") {
    group = "verification"
    description = "校验 Echoes 的 mumu 结构、旧存档别名与唯一放置集合。"
    workingDir = rootProject.layout.projectDirectory.asFile
    inputs.file(structureIdScript)
    inputs.dir(echoesPackRoot)
    commandLine(pythonExecutable, structureIdScript.absolutePath,
        "--pack", "echoes", "--root", echoesPackRoot.absolutePath)
}
val verifyDntStructureLayout = tasks.register<Exec>("verifyDntStructureLayout") {
    group = "verification"
    description = "校验 DnT 的 mumu 结构、旧存档别名与唯一放置集合。"
    dependsOn(prepareDntResources)
    workingDir = rootProject.layout.projectDirectory.asFile
    inputs.file(structureIdScript)
    inputs.dir(dntGeneratedDir)
    commandLine(pythonExecutable, structureIdScript.absolutePath,
        "--pack", "dnt", "--root", dntGeneratedDir.get().asFile.absolutePath)
}
tasks.register("verifyStructureLayout") {
    group = "verification"
    description = "统一校验内置两包的 11 个公开结构与旧存档兼容布局。"
    dependsOn(verifyEchoesStructureLayout, verifyDntStructureLayout)
}

// 产物校验同时包含结构布局与 Echoes 真实 codec；失败即阻断构建与发布。
tasks.named("verifyJar") {
    dependsOn("verifyEchoesCodecs", "verifyStructureLayout")
}

// ---------------------------------------------------------------------------
// Python 侧单测（DnT 迁移工具）纳入 check。
// 这批测试自带 fixture，在临时目录自行跑 prepare_dnt.main 并断言契约，因此不依赖
// prepareDntResources 的生成产物。
// ---------------------------------------------------------------------------
tasks.register<Exec>("pythonTests") {
    group = "verification"
    description = "运行 scripts/tests 下的 DnT 迁移工具单元测试。"
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine(
        pythonExecutable,
        "-m", "unittest", "discover",
        "-s", rootProject.file("scripts/tests").absolutePath,
        "-v"
    )
}

tasks.named("check") {
    dependsOn("pythonTests")
}

// 构建必须经过产物校验：verifyJar 自身 dependsOn(shadowJar)，
// 故此处只挂 verifyJar，即「先出 Shadow 产物、再校验通过」才会构建成功。
tasks.named("assemble") {
    dependsOn("verifyJar")
}
tasks.named("build") {
    dependsOn("verifyJar")
}

// ---------------------------------------------------------------------------
// 发布：只把最终 Shadow 产物发到 C:/PluginLibs/Maven（不替换核心或其它插件产物）。
// 实际 publish / 交付复制由主集成后执行。
// ---------------------------------------------------------------------------
publishing {
    publications {
        create<MavenPublication>("betterend") {
            groupId = project.group.toString()
            artifactId = "BetterEnd"
            version = project.version.toString()
            artifact(shadowJarTask)
            pom {
                name.set("BetterEnd")
                description.set(
                    "Server-side BetterEnd for Paper/Folia 26.3, powered by CraftEngine. " +
                        "Mixed licensing: the adaptation (dev.mumu.betterend) is MIT; the bundled " +
                        "DnT End Castle Standalone v1.3.4 data/assets are All Rights Reserved and are " +
                        "included for the user's local use only (no public redistribution asserted). " +
                        "YUNG's Better End Island structure templates are LGPL-3.0. " +
                        "See LICENSE, LICENSE.ASSETS and THIRD-PARTY-NOTICES."
                )
                licenses {
                    license {
                        name.set("MIT (this adaptation only)")
                        url.set("https://opensource.org/license/mit")
                    }
                    license {
                        name.set(
                            "LicenseRef-All-Rights-Reserved (DnT End Castle Standalone v1.3.4 " +
                                "data/assets; local integration only, not redistributable)"
                        )
                    }
                    license {
                        name.set("LGPL-3.0 (YUNG's Better End Island structure templates)")
                        url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                    }
                }
                developers {
                    developer {
                        id.set("linmumua")
                        name.set("linmumua")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "pluginLibs"
            url = uri("C:/PluginLibs/Maven")
        }
    }
}

// 发布路径同样先过产物校验：只有通过 verifyJar 的 Shadow 产物才会被发布。
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn("verifyJar")
}
