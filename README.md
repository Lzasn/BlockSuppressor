> [!WARNING]
> **本项目全程 100% 使用 Vibe Coding 开发。**
>
> 如果你介意这一点，请不要使用本项目的源代码以及编译产物。
> 代码只在有限场景下验证过，**存在 bug 在预期范围内**，按 "AS IS" 提供，不附带任何保证。
> 继续使用即表示风险自负。
>
> *This project is 100% vibe-coded. If that bothers you, please don't use the source code or the compiled artifacts. Bugs are expected.*

# BlockSuppressor

Folia 26.2 的「方块更新抑制器」插件。判定方式很简单：

> **把容器拿到铁砧上改名为 `方块抑制器` 或 `Block Suppressor`，它就成了抑制器。**
> 旁边接一个背离它、指向目标的比较器，比较器指向的那一格成为抑制域中心。

```
[ 容器(已改名) ] [ 比较器 ] → [ 被抑制的方块 ]
```

拆掉下界门框架时门不会碎（切门），沙砾/沙子不会掉，需要支撑的方块也不会掉。
接上比较器后容器上方会**一直**浮着 `Block Suppressor` 这个标签。

---

## 它到底在做什么

原版抑制器靠抛异常打断「当前 tick 剩余的更新链」。插件没法往更新链里丢异常，
但能卡在同一条链的入口上。反编译 `shiroha-26.2` 的 `net.minecraft.world.level.Level`：

```
notifyAndUpdatePhysics(...)
  ├─ updateNeighborsAt(...)                     // 邻居更新（redstone / neighborChanged）
  ├─ new BlockPhysicsEvent(block, newState)     // ← 插件在这里介入
  │     if (isCancelled()) return;              // ← 取消后直接跳过下面两行
  ├─ updateNeighbourShapes(...)                 // 形状更新
  └─ updateIndirectNeighbourShapes(...)         // 间接形状更新
```

下界门方块 `NetherPortalBlock.updateShape()` 一旦发现框架不全就返回空气，
而它正是被 `updateNeighbourShapes` 调起来的。所以：**取消该方块位置上的
`BlockPhysicsEvent`，它的形状更新就传不出去，门就保住了。**

这也解释了为什么网上很多「监听传送门方块的 BlockPhysicsEvent」的写法不管用——
事件是针对**发生变化的方块**（框架）抛的，不是针对门方块抛的。

## 用法

1. 铁砧里把容器改名为 `方块抑制器` 或 `Block Suppressor`。
   匹配时忽略大小写、忽略半角/全角空格，所以 `block suppressor`、`BlockSuppressor`、`方块 抑制器` 都算。
2. 放下这个容器。
3. 在它旁边放一个比较器，让比较器**背离容器**、指向你要抑制的方块（比如门框架）。
4. 容器上方浮出 `Block Suppressor`，之后拆框架门不会碎。

只支持三种容器方块：**箱子 `CHEST`、陷阱箱 `TRAPPED_CHEST`、末影箱 `ENDER_CHEST`**（`allowed-containers` 可改）。
普通方块（石头、铁块）改名放下不会生效，因为方块状态里没有名字可存。

> **末影箱的坑**：原版 `EnderChestBlockEntity` **没有实现 `Nameable`**，铁砧改的名字在放下的一瞬间就丢了——
> 方块实体里没有任何字段能存。所以插件在放置时读取物品名，往末影箱的方块实体 PDC 里写一个标记
> （`blocksuppressor:suppressor`），持久化在区块里，效果等价于"改过名"。
> 注意：末影箱被拆掉后掉落的还是普通末影箱，名字不会回来。

状态说明：

| 状态 | 含义 |
| --- | --- |
| 待机 | 容器已改名，但旁边没有指向外部的比较器 → 不抑制任何方块，**也不显示标签** |
| 已接比较器 | 比较器指向的方块成为抑制域中心，开始抑制，容器上方浮出标签 |

拆掉容器 → 注销；拆掉比较器 → 回到待机，标签立即消失。
右键旋转比较器会自动重新解析指向。

## 悬浮标签

**接上比较器的瞬间**才在容器正上方生成一个 `TextDisplay`，拆掉比较器即消失：

- 常驻显示，直到比较器/容器被拆或 `/suppressor remove`
- `non-persistent`，区块卸载时随区块丢弃，区块重新加载时（若仍接着比较器）自动重建
- 渲染：居中、始终面向玩家、半透明黑底、不穿墙

```yaml
hologram:
  enabled: true
  text: "Block Suppressor"
  height: 1.45    # 相对容器底部的悬浮高度
```

## 容器锁定

被识别为抑制器的容器会被**锁死**，防止有人打开它、或把里面的物品用漏斗薅走：

- 玩家右键打开 → 取消打开，物品栏上方红字提示（文案可配 `locked-message`）
- 漏斗 / 漏斗矿车 / 投掷器 双向搬运 → 全部拦截（`InventoryMoveItemEvent`，源或目标命中就取消）
- **不会拼成大箱子**：放置不拦，落地后插件把相邻的两只都强制改回 `ChestType.SINGLE`——原版合并要求邻居不是 SINGLE，所以拆开后不会再被形状更新拼回去，各自 27 格独立

末影箱走的是另一条路：它打开的是玩家自己的末影物品栏，从 `Inventory` 反查不到是哪一格方块，
所以改成在 `PlayerInteractEvent` 这一层拦右键——**手里拿着方块（比如比较器）时放行**，空手/非方块物品才拦，
这样不影响在它旁边搭红石。末影箱本身也不能被漏斗访问，无需额外处理。

锁定只拦「打开」「物品搬运」，**不影响**在旁边放比较器，也不影响拆除。

## 挂载实体

展示框、荧光展示框、画、拴绳结这类**挂在方块上的实体**不吃方块更新——它们每 tick 自己检查
`survives()`，支撑方块没了就自毁（触发 `HangingBreakEvent`，原因 `PHYSICS` / `OBSTRUCTION`）。

插件在抑制域内取消这两个原因的事件，所以拆掉墙/地板后它们会跟着悬空保留。
玩家自己打掉的（原因 `ENTITY`）照常能拆。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/suppressor list` | 列出已登记的抑制器（别名 `/bss`） |
| `/suppressor remove` | 移除离你最近的一个（8 格内），并清掉名字/标记 |
| `/suppressor reload` | 重载 `config.yml` |

权限：`blocksuppressor.admin`（默认 OP）。

## 配置 `config.yml`

```yaml
radius: 1        # 抑制域半径，1 = 3x3x3，0 = 只抑制指向的那一格
max-radius: 3    # 半径上限

locked-message: "该容器已被锁定为方块抑制器，无法打开"   # 试图打开时的红字

suppressor-names:        # 铁砧改名后的合法名字（忽略大小写与空格）
  - "方块抑制器"
  - "Block Suppressor"

allowed-containers:      # 允许作为抑制器的容器方块
  - CHEST
  - TRAPPED_CHEST
  - ENDER_CHEST

hologram:
  enabled: true
  text: "Block Suppressor"
  height: 1.45
```

## 构建

```bat
build.bat                              :: 用 ..\test\26.2 作为服务端目录
build.bat "D:\servers\folia"           :: 指定服务端目录
build.bat "D:\servers\folia" install   :: 顺便复制到 plugins\
```

脚本会自动找 JDK 25（`JAVA_HOME` → `C:\Program Files\Microsoft\jdk-25*` → `C:\Program Files\Java\jdk-25*`），
从服务端目录里定位 `shiroha-api`、`adventure-api`、`adventure-key`、
`adventure-text-serializer-plain`、`guava`、`bungeecord-chat` 作为编译类路径，
加上仓库里自带的 `libs\annotations-26.0.2.jar`（只是 javac 需要的注解，不会打进插件）。全程不联网。
产物：`build\BlockSuppressor-1.3.0.jar`。

目录：

```
BlockSuppressor/
├─ build.bat
├─ libs/annotations-26.0.2.jar
├─ src/main/java/com/lzasn/blocksuppressor/
│   ├─ BlockSuppressorPlugin.java   插件入口 / 配置读取
│   ├─ Names.java                   铁砧名字匹配（忽略大小写与空格）
│   ├─ Targets.java                 抑制器判定（名字 / 末影箱 PDC 标记）
│   ├─ Suppressor.java              一个抑制器：容器 + 抑制域 + 标签
│   ├─ SuppressorManager.java       注册表、区块索引、区块加载扫描
│   ├─ SuppressorListener.java      放置/破坏/物理事件/区块加载卸载
│   ├─ Hologram.java                容器上方的常驻 TextDisplay
│   └─ SuppressorCommand.java       /suppressor
├─ src/main/resources/{plugin.yml,config.yml}
└─ build/BlockSuppressor-1.3.0.jar
```

## 兼容性

- 目标：**26.2 的 Folia 系服务端**（Folia 官方、Shiroha、以及保留了 Folia region 调度 API 的分支）。
  只用了 Paper/Folia 公开 API，同一份 jar 在 **Paper 26.2** 上也能跑（`RegionScheduler` 映射回主线程）。
- 只引用 `org.bukkit.*`、`io.papermc.paper.threadedregions.scheduler.*`、`net.kyori.adventure.*`，
  字节码里没有 fork 专有类。
- 硬性要求：服务端认识 `api-version: '26.2'`、运行 Java 25（class 文件版本 69）。
- **唯一的兼容性风险**：个别"优化端"为了性能把 Paper 在 `Level.notifyAndUpdatePhysics` 里的
  `BlockPhysicsEvent` 调用删了/改了。那种端上插件会照常提示"已接入"，但门照碎——因为钩子没了。
- 低版本服务端会被 `api-version` 直接拒绝；Spigot 不行（没有 RegionScheduler）。

## 已知边界

- 抑制域是**有限立方体**，不是原版那种「打断整条更新链」。半径 1 足够切门。
- 一个容器只认**一个**比较器；旁边接多个时按 北/东/南/西/上/下 的顺序取第一个。
- 由别的方块**形状更新**推过来的变化不受影响（`executeShapeUpdate` 没有 Bukkit 事件），
  所以域内方块仍可能被外部形状更新清掉——原版也有类似限制。
- 半径改动在下一次接上比较器时生效（索引按半径建，重载不会搬动已建好的索引）。
- 容器被活塞推走/被爆炸炸掉不会触发 `BlockBreakEvent`，要等区块重新加载时扫描才会清理。
- 抑制域内红石、沙子、水流都会「僵住」，这是预期行为，别贴着机器盖。

## 许可证

[MIT](LICENSE) © Lzasn
