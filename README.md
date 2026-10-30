# BetterEnd

把 Fabric 模组 [Better End](https://github.com/quiqueck/BetterEnd)（原 Team BetterX）的末地内容，重新实现为 **Paper / Folia 26.3 服务端插件**，并整合 DnT（Dungeons and Taverns）末地城堡。

所有方块、物品、模型、配方与翻译由 [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 提供，插件本体只负责世界生成、群系与结构。

## 特性

- **末地地形与群系**：27 个末地群系，含浮空岛、石灰岩荒原、琥珀之地等
- **洞穴与矿物**：末地洞穴系统与多种矿物
- **植被**：九种自定义树木与大量花卉植被
- **DnT 末地城堡**：城堡本体、飞船、战利品与附魔（随包归档，构建期解压）
- **原版行为对齐**：柱晶生命周期、结构世界守卫、装饰陶罐破坏、怪物伤害倍率

## 环境要求

| 项目 | 版本 |
|---|---|
| 服务端 | Paper 26.3 或 Folia 26.3 |
| Java | 25 及以上 |
| 必需前置 | CraftEngine（26.10+） |

## 安装

1. 把 `BetterEnd-<version>.jar` 放进 `plugins/`
2. 安装并启用 **CraftEngine**
3. 启动服务端。插件首次启动会释放两个 CraftEngine 资源包：
   - `plugins/CraftEngine/resources/betterend/`
   - `plugins/CraftEngine/resources/dnt_end_castle/`
4. **再重启一次**，让 CraftEngine 在资源包已就位的情况下完成扫描

新建末地世界时使用本插件的地形生成器。

## 构建

需要 JDK 25。

```bash
./gradlew clean shadowJar
```

产物：`jars/BetterEnd-<version>.jar`

### 关于 `upstream/`

本仓库**不包含**上游模组源码。构建前请自行准备：

```
upstream/BetterEnd-Plugin/      # 上游 BetterEnd-Plugin 的源码树
```

路径由 `gradle.properties` 指定。缺少该目录时，资源生成步骤会失败并指出缺什么。

## 配置

- `plugins/BetterEnd/config.yml` — 运行期配置
- `plugins/BetterEnd/messages.yml` — 全部玩家可见文案（MiniMessage 格式）

两个文件都带 `config-version`：升级时只补回缺失键，**不会覆盖你改过的值**。

## 已知限制

- **部分方块属性未移植**：`age`（生长阶段）、`shape`（分段形态）、`top` 等属性会影响上游的模型外观；本版本对其中大部分只声明了属性名、未逐一复刻全部档位的独立模型。启动日志会逐条列出仍缺的属性，可按需评估
- **DnT 城堡为归档整合**：构建期从随包归档解压，版本跟随归档，不跟随 DnT 上游更新

## 许可

- 本移植：MIT
- 上游 Better End：MIT，Copyright (c) 2020 paulevsGitch and the Better End contributors
- 美术资源：见 `LICENSE.ASSETS`
- DnT 与其他第三方组件：见 `THIRD-PARTY-NOTICES`

## 致谢

- [Team BetterX](https://github.com/quiqueck/BetterEnd) —— 原 Better End 模组
- DnT（Dungeons and Taverns）—— 末地城堡
- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) —— 方块/物品/模型运行时
- [PaperMC](https://papermc.io/) / [Folia](https://github.com/PaperMC/Folia) —— 服务端
