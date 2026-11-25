# DupeGuard

> v1.1.0：新增非法植株自动清除与 `/dupeguard purge`，并修正日志重复前缀。

针对 **Minecraft 1.21.11** 服务端的复制漏洞修复插件（Paper / Spigot / Purpur / Folia 及下游核心）。

纯 Bukkit/Paper 事件层实现，**不依赖 NMS、不依赖 ProtocolLib**，因此跨版本与跨 fork 稳定。

---

## 修复的两个漏洞

### 1. 小型垂滴叶复制（Dripleaf Universal Dupe）

影响 Spigot 系 1.17 – 26.1.2。2026-11-15 起在 3C3U 等服被大规模利用。

**根因**：Spigot 在骨粉生长时用 `captureTreeGeneration` 做方块变更捕获。当对「结构不完整的小型垂滴叶」（用更新跳过器造出的单半截植株）使用骨粉时，生长逻辑提前返回，捕获标记没有被清除，于是世界开始吞掉方块写入——破坏方块会掉落物品但方块本身不消失，可以无限重复破坏刷物品。

**修法**：漏洞必须先有一个「结构不可能存在」的植株，且必须对它成功执行生长。两者都能从 Bukkit API 观测，所以在入口处拒绝：

- `BlockFertilizeEvent`（HIGHEST）：结构异常时取消骨粉，从源头阻止标记泄漏
- `BlockBreakEvent` / `PlayerInteractEvent`：异常植株禁止破坏与交互
- **拦截后自动清除**：非法植株下一 tick 被移除，不留悬空残骸
- 生长成功后延迟一 tick 校验产物，清理畸形大型垂滴叶

> 为什么要清除：正常双格垂滴叶被打掉下方方块时，原版 `canSurvive` 邻居更新会在约 2 秒内整株销毁（已实测）。而用更新跳过造成的 `ORPHAN` 单半截植株**绕过了更新逻辑**，邻居更新永远传不到它，于是长期悬空、反复诱发尝试。清除它才是恢复合法世界状态。

### 2. 收纳袋断线复制（Bundle / Netty dupe）

影响 1.21.2+。

**根因**：客户端用超量封包把 Netty 管道堵住，连接看似存活但停滞。此时排队的 `use item` 在玩家实体已被保存并移出世界之后才被处理，收纳袋内容从一个「已不存在的实体」掉落出来，而存档里收纳袋仍是满的 → 内容翻倍。

**修法**：

- `PlayerQuitEvent`（LOWEST）清除活跃物品（上游修法，队列里的 use 无内容可释放）
- `PlayerChangedWorldEvent` 同样清除，堵住相同的「实体已走、连接还在」窗口
- 收纳袋使用限速 + 滑动窗口，拒绝不可能的封包突发

---

## 安装

1. 把 `DupeGuard-1.0.0.jar` 放进服务端 `plugins/`
2. 重启服务器
3. 控制台应出现：`[DupeGuard] DupeGuard enabled with 2 active module(s).`

要求 Java 21+，`api-version: 1.21`。

## 配置

见 `plugins/DupeGuard/config.yml`，两个模块可独立开关。关键项：

| 配置 | 默认 | 说明 |
|---|---|---|
| `dripleaf.block-broken-dripleaf-bonemeal` | `true` | 拦截破损植株的骨粉 |
| `dripleaf.sanitize-after-grow` | `true` | 生长后校验产物 |
| `dripleaf.auto-purge-illegal` | `true` | 拦截后自动清除非法植株 |
| `bundle.clear-active-item-on-quit` | `true` | 退出时清活跃物品（核心修法） |
| `bundle.bundle-use-cooldown-ms` | `250` | 收纳袋使用冷却 |
| `bundle.max-use-item-per-window` | `12` | 滑动窗口内最大使用次数 |

## 命令

```
/dupeguard status                      查看模块状态与被拦截次数
/dupeguard reload                      重载配置（模块开关需重启）
/dupeguard check <x> <y> <z>           游戏内检查某坐标的植株结构
/dupeguard check <world> <x> <y> <z>   控制台检查（需区块已加载）
/dupeguard purge [world] [radius]      扫描并清除范围（默认 64）内的非法植株
```

`check` 输出示例：

```
world 10,-60,10 SMALL_DRIPLEAF -> ORPHAN (WOULD BE BLOCKED)   # 破损，会被拦
world 14,-60,10 SMALL_DRIPLEAF -> HEALTHY (ok)                # 正常，放行
```

权限：`dupeguard.admin`（默认 OP）。

---

## 真机验证记录

环境：**Paper 1.21.11 build 132**，Java 21，实例 107（TMP Server）。

插件加载：

```
[DupeGuard] Enabling DupeGuard v1.0.0
[DupeGuard] DupeGuard enabled with 2 active module(s).
```

结构判定（`/dupeguard check`）：

| 场景 | 结构 | 判定 | 预期 |
|---|---|---|---|
| 只有上半截 | `ORPHAN` | `WOULD BE BLOCKED` | ✅ |
| 只有下半截 | `ORPHAN` | `WOULD BE BLOCKED` | ✅ |
| 正常上下双半块 | `HEALTHY` | `ok` | ✅ 放行 |
| 普通方块（石头/钻石块） | `HEALTHY` | `ok` | ✅ 不误伤 |

> 说明：单格放置双格植物的另一半时，原版会自行移除该格（`setblock` 报告成功但读回 `AIR`），这是原版行为，与插件无关。完整植株需两格依次放置。

---

## 与官方修复的关系

上游 Paper 已在新版本修复部分复制向量。**能升级服务端时优先升级**——本插件用于无法立即升级（插件生态锁定、自定义核心、旧版本存档）时的应急与纵深防御。两者不冲突，可同时启用。

## 更新日志

### v1.1.0 (2026-11-24)
- 新增 `dripleaf.auto-purge-illegal`：拦截非法植株后自动清除，不再留悬空残骸
- 新增 `/dupeguard purge [world] [radius]`：批量扫描清除历史遗留的非法植株
- 修正日志重复前缀（`[DupeGuard] [DupeGuard] ...` → `[DupeGuard] ...`）

### v1.0.0 (2026-11-21)
- 首个版本：垂滴叶复制 + 收纳袋断线复制修复

## 许可

GPL-3.0
