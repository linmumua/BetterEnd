import org.gradle.language.jvm.tasks.ProcessResources

// 依赖的实际 jar（仅编译期；运行期由服务端 / CraftEngine 插件提供）。
val mucoreApi = rootProject.file(providers.gradleProperty("mucoreApiJar").get())
val mucoreServer = rootProject.file(providers.gradleProperty("mucoreServerJar").get())
val craftEngine = rootProject.file(providers.gradleProperty("craftEngineJar").get())
val snakeyamlVersion = providers.gradleProperty("snakeyamlVersion").get()

// server jar 的「仅编译可见」配置。**故意不放进 compileOnly**：
// 根脚本用 testImplementation/testRuntimeOnly extendsFrom(compileOnly) 复用依赖，一旦 server jar
// 落在 compileOnly 里，它就会进测试**运行期**类路径，于是测试开始加载 server jar 里的实现类
// （实测最先炸的是 io.papermc.paper.adventure.PaperAdventure 的静态初始化，它要 netty 的
// AttributeKey，而本模块既没有也不该引入那一串启动库），API/资源类测试跟着整片红。
// 需要它的只有 javac：compileJava 要解析 MuDragonFightBridge 用到的 NMS 符号，
// compileTestJava 要解析 MuDragonFightBridgeTest#symbolCanary（该方法永不被调用）。
// 只挂 compileClasspath / testCompileClasspath —— 测试运行期看不到它，也就不会加载 NMS。
val mucoreServerCompileOnly = configurations.create("mucoreServerCompileOnly") {
    isCanBeConsumed = false
    isCanBeResolved = false
}

dependencies {
    // MU核心 / Folia 26.3 API（内含 org.bukkit.* 与 io.papermc.* 的 Paper API）。
    compileOnly(files(mucoreApi))
    // MU核心 26.3 的 server jar：**只给 MuDragonFightBridge 编译期链接**真实 NMS 符号。
    // 排在 API 之后（org.bukkit.* 以 API jar 的为准），且绝不打包、绝不进测试运行期（见上面配置）。
    // 运行期这些类由服务端提供；版本/依赖不符时桥接自己捕获 LinkageError 后关停。
    add(mucoreServerCompileOnly.name, files(mucoreServer))
    // DataFixerUpper：server jar 里只带了 Paper 自己 patch 过的 com.mojang.datafixers.DataFixerBuilder
    // 与 util.Either，com.mojang.datafixers.util.Pair 仍在该库里。NMS 签名（BlockPattern /
    // EnderDragonFight）解析时会碰到它，缺了就是「cannot access com.mojang.datafixers.util.Pair」。
    // 版本取 MU核心 26.3 服务端实际加载的那一份：run/libraries/com/mojang/datafixerupper/10.0.21
    // （753,612 字节，与 Gradle 缓存中的 10.0.21 同体量），不是猜的。
    // 仍是 compileOnly —— 运行期由服务端自带，绝不随产物打包。
    compileOnly("com.mojang:datafixerupper:10.0.21")
    // CraftEngine 插件 jar（已内嵌 craft-engine-core / craft-engine-bukkit）。
    compileOnly(files(craftEngine))
    compileOnly("org.jetbrains:annotations:26.1.0")
    // 运行期 SnakeYAML 由 :plugin 打包并重定位；本模块只在编译期引用。
    compileOnly("org.yaml:snakeyaml:$snakeyamlVersion")

    // 测试类路径已由根脚本 extendsFrom(compileOnly) 继承 Bukkit / Paper API 与 SnakeYAML。
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// server jar 只进这两条**编译**类路径（追加在 API 之后，保持 mucore-api → server 的解析顺序）。
// 不挂 testRuntimeClasspath / testRuntimeOnly：那里只应该有 API 与纯库，没有 server 实现。
configurations.named("compileClasspath") { extendsFrom(mucoreServerCompileOnly) }
configurations.named("testCompileClasspath") { extendsFrom(mucoreServerCompileOnly) }

// DnT 资源契约测试读取 :plugin 生成的资源目录（datapacks/ + craftengine/）。
// 无条件挂到固定任务，避免脚本后来才落地时依赖没有建立。
val dntGeneratedDir = rootProject.layout.projectDirectory.dir("plugin/build/generated/dnt")
tasks.named<ProcessResources>("processTestResources") {
    from(dntGeneratedDir)
    dependsOn(":plugin:prepareDntResources")
}
