# Syna Mod 兼容性问题诊断（2026-05-22）

针对你提到的 4 个症状，逐条给根因，再列修复路径。

---

## 症状 1：随机进入主世界后周围一片"none"

日志特征：
```
[Forge-Spawn] 8s without spawn — forcing spawn event for Forge compatibility
syna spawned.
...
Agent executed: !nearbyBlocks and got: NEARBY_BLOCKS: none
```

### 根因
`mindcraft-develop/src/utils/mcdata.js` 里我之前写的 "Forge Spawn Fallback" 逻辑：

```js
// Force spawn fallback: if after 8 seconds still no spawn, force it
const forceSpawnTimeout = setTimeout(() => {
    if (!hasSpawned) {
        if (bot.entity) {
            bot.emit('spawn');
        } else {
            bot.entity = bot.entity || { position: { x: 0, y: 64, z: 0 }, ... };
            bot.emit('spawn');
        }
    }
}, 8000);
```

这段代码是把症状当病治。它的假设是"position 包被 parse error 吞了"，于是 8 秒后强制 `emit('spawn')` 并给 `bot.entity` 塞一个 `(0, 64, 0)` 的假坐标。

但实际情况是：
1. Forge 1.20.1 整合包确实会触发若干 PartialReadError，但 **position 包本身从不会被 parse error 影响**（vanilla 包永远能解析）
2. 真正延迟 spawn 的原因是 `chunk_batch_start / chunk_batch_finished` 类的 mod 包被 mineflayer 等待
3. 8 秒强 spawn 时 bot.world 还没装载完区块 → `world.getNearbyBlocks()` 自然返回空 → `NEARBY_BLOCKS: none`
4. 假坐标 `(0, 64, 0)` 让 pathfinder 用错误起点算路径，触发服务端反作弊 → 后续 cleanKill 兜底掉线

### 为什么"随机"
看 spawn 时实际接收 chunk 数据的速度。机器/网络快时区块在 8 秒内到达，能正常 spawn；慢时被强 spawn 截胡，世界为空。

### 修复
撤销这段 force spawn，恢复 30 秒纯警告版本，让 mineflayer 自己等 position：见下面"修复 1"。

---

## 症状 2：进入暮色森林完全退化、人物模型卡死、破坏方块身体浮空

### 根因
暮色维度切换 = 一次完整的 `respawn` 包。Forge 1.20.1 在维度切换时会重新下发 mod sync 包（registry / config），同样会被 minecraft-protocol 吐 PartialReadError。

mineflayer 在 `respawn` 后等服务端的新 position。如果服务端在配置同步还没结束之前没发 position，且我们的 force spawn 又在 8 秒后第二次强 emit `spawn`：
- bot.entity.position 仍是切维度前的旧坐标（主世界）
- mineflayer 内部地图引用全部失效
- 角色模型在客户端看上去定在原地（因为 syna 本地认为自己还没动）
- 破坏方块时身体"浮空"也是同一个原因：syna 认为自己脚下是主世界的方块，实际服务端那边脚下没东西，于是出现脚下空气、视觉上飘起来

### 修复
同样靠"撤销 force spawn"。重要：**应该让 spawn 自然由 position 包触发**。如果偶尔需要超时，把超时拉到 60 秒并且只打印警告，不强 emit。

---

## 症状 3：mod 实体名永远是 unknown

日志：
```
[mod_registry] captured: items=1255 blocks=1003 entities=124 namespaces=1
```

### 根因
`namespaces=1` 是关键证据。1255/1003/124 都是 **vanilla 的数量**（vanilla 1.20.1 实体正好 124 种）。说明 `mod_registry._harvestFromBot()` 走通了，但只拿到了 mineflayer 内置 minecraft-data 的注册表，没拿到 mod 注册表。

为什么？因为：
- mineflayer 的 `bot.registry` 是它自己加载的 minecraft-data，**永远不含 mod 信息**
- 真正的 mod 实体名只在登录配置阶段（1.20.1 后是 `configuration` state）的 `registry_data` 包里
- 我的 `_harvestFromRegistryDataPacket` 监听了 `registry_data`，但你看代码：1.20.1 的 codec 里只有 `dimension_type / biome / chat_type / damage_type / trim_pattern / wolf_variant`，**根本没有 entity_type / item / block 列表**
- mod 实体的真正注册表在 `forge:registry` 这个 custom_payload 通道里，需要专门解析

所以 mineflayer 解析数字 entityType ID（比如 twilight forest naga 的 ID 在原版表里查不到）→ 返回 `name = 'unknown'`。

### 修复路线（按工作量从小到大）
1. **最快**：让 syna_mod（你已经有的 BridgeHttpServer）暴露一个 `/registry/dump` 端点，返回服务端 `BuiltInRegistries.ENTITY_TYPE` 的全表。客户端启动时 fetch 一次，灌入 `mod_registry`。本仓库已有 `BridgeHttpServer.java`，加 50 行就能完成。
2. **中等**：解析 `forge:registry` custom_payload 包，从字节流里抽 `entity_type` 列表
3. **慢**：等 mineflayer / minecraft-protocol 更新支持 forge handshake v3

推荐走方案 1。

---

## 症状 4：mod 胸甲装备时退化成铁甲，死亡掉落却是 mod 胸甲

### 根因
这是 **`mineflayer-armor-manager` 插件**的问题，不是 mod_registry 的锅。

`mcdata.js` 里：
```js
bot.loadPlugin(armorManager); // auto equip armor
```

armorManager 内部检查物品 `material` 字段决定 slot：
- 它用 vanilla 的 `iron_chestplate / diamond_chestplate / netherite_chestplate` 名字匹配
- mod 胸甲的 `name` 是 `someaddon:custom_chestplate`，不在它的白名单里
- armorManager 看到不认识的 chestplate → fallback 到 vanilla iron_chestplate 的 slot 视图（**只是 mineflayer 端的本地 displayName 渲染**）
- 服务端实际 ItemStack 仍然是 mod 物品 → 死亡掉落正确

也就是说：
- 装备成功了（slot 5），mod 胸甲穿上了
- 但 syna 自己看 `bot.inventory.slots[5]` 时，prismarine-item 用 vanilla 数据库渲染了 displayName 为 "Iron Chestplate"
- 这是显示退化，不是装备退化

### 修复（任选其一）
1. **快**：profile 里关闭 auto_equip_armor，syna 自己 `bot.equip(item, 'torso')`，直接把 NBT/数字 ID 传给服务端
2. **彻底**：把 mod_registry 的 itemsByNumericId 接进 `prismarine-item` 的 displayName 解析（运行时给 Item.prototype.displayName 打补丁）

---

## 修复优先级

| # | 问题 | 优先级 | 文件 | 状态 |
|---|------|--------|------|------|
| 1 | 撤销 force spawn → 解决症状 1+2 | P0 | `src/utils/mcdata.js` | ✅ 本次提交 |
| 2 | mod 实体 unknown → 走 BridgeHttpServer dump 注册表 | P1 | `syna_mod/.../BridgeHttpServer.java` + `mod_registry.js` | 待做 |
| 3 | 物品 displayName 退化 | P2 | `mcdata.js` 的 `Item` 用法或 profile | 待做 |

---

## 关于 cleanKill 掉线

```
raw reason: cleanKill: Code execution refused stop after 10 seconds. Killing process.
```

这是 `actions.js` 里 `runAction` 的兜底：执行命令时超过 10 秒不响应就强杀。
触发场景：force spawn 后 pathfinder 用假坐标算路径，永远算不出来 → 卡住 → 兜底超时 → 进程退出。

撤销 force spawn 之后这条会自动消失。
