# Syna × Forge 1.20.1 暮色森林兼容性方案

## 问题背景

Forge 1.20.1 使用 FML3 协议进行 mod 握手。当服务端安装了暮色森林（Twilight Forest）等 mod 时，vanilla 协议客户端（mineflayer/minecraft-protocol）无法完成握手，导致：

1. **连接被拒绝** — 缺少 `\0FML3\0` 标记
2. **Login 阶段被踢** — 无法回复 `login_plugin_request`（fml:loginwrapper）
3. **Play 阶段卡住** — Forge 在 play 阶段通过 `fml:handshake` channel 发送 mod 配置同步，bot 不回复导致 position 包延迟
4. **Parse error** — Forge mod 的自定义包格式无法被 minecraft-protocol 解析

## 解决方案（多层防御）

### 第 1 层：客户端 FML3 握手注入（mcdata.js）

```
位置: mindcraft-develop/src/utils/mcdata.js
```

- **FML3 标记注入**: 拦截 `set_protocol` 包，在 `serverHost` 字段追加 `\0FML3\0`
- **Login 握手处理**: 实现完整的 FML3 login_plugin_request 协议
  - 解析 `fml:loginwrapper` 包装格式
  - 回复 `C2SModListReply`（discriminator=2，所有 count=0）
  - 后续包回复 acknowledge（0x63）
- **Play 阶段自动 ACK**: 监听 `custom_payload` 中的 `fml:handshake` / `forge:handshake`，自动回复 0x63
- **Parse error 抑制**: 拦截 `PartialReadError` / `Parse error for play.toClient`，避免 bot 崩溃
- **Spawn 超时检测**: login 后 30 秒无 spawn 事件时输出警告日志

### 第 2 层：服务端 FmlBypass（Forge Mod）

```
位置: Python souls chatpgt/syna_mod/src/main/java/com/syna/bridge/FmlBypass.java
```

- 在服务端禁用对 bot 玩家的 Forge channel 验证
- 跳过 mod 同步要求，允许 vanilla 协议客户端加入

### 第 3 层：服务端 ForgeSpawnHelper（Forge Mod）

```
位置: Python souls chatpgt/syna_mod/src/main/java/com/syna/bridge/ForgeSpawnHelper.java
```

- 检测 bot 玩家加入（通过 BotIdentity）
- 延迟 1.5 秒和 3.5 秒各发送一次 position 包
- 确保即使第一次 position 被 parse error 吞掉，bot 仍能收到后续的 position 触发 spawn

## 部署步骤

### 1. 编译 syna_mod

```bash
cd "Python souls chatpgt/syna_mod"
gradlew build
```

### 2. 安装到 Forge 服务端

将编译产物 `build/libs/synabridge-*.jar` 复制到：
```
D:\Minecraft\1.19.2\versions\1.20.1`forge\mods\
```

### 3. 启动服务端

正常启动 Forge 1.20.1 服务端（含暮色森林等 mod）。

### 4. 启动 Syna bot

```bash
cd mindcraft-develop
node main.js
```

Bot 会自动：
1. 注入 FML3 标记 → 通过初始握手
2. 回复 login_plugin_request → 完成 FML3 login 阶段
3. ACK play 阶段的 fml:handshake → 不阻塞 position 发送
4. 抑制 Forge mod 包的 parse error → 不崩溃
5. 收到 ForgeSpawnHelper 重发的 position → 触发 spawn

## 日志关键字

| 日志前缀 | 含义 |
|---------|------|
| `[FML3]` | 客户端 FML3 握手过程 |
| `[FML3-play]` | Play 阶段 channel ACK |
| `[Forge-Spawn]` | Spawn 超时警告 |
| `[SynaSpawnHelper]` | 服务端 position 重发 |
| `[mcdata] Suppressed Forge parse error` | 被抑制的 parse error |

## 兼容的 Mod 列表

理论上兼容所有 Forge 1.20.1 mod，因为：
- FmlBypass 跳过了所有 mod channel 验证
- 客户端不需要真正加载任何 mod
- Parse error 被抑制不影响核心游戏逻辑

已测试/目标：
- ✅ Twilight Forest（暮色森林）
- ✅ 其他 Forge 1.20.1 mod

## 注意事项

1. **Bot 无法使用 mod 物品** — bot 的 minecraft-data 只包含 vanilla 物品。mod 物品通过 mod_registry 模块部分支持。
2. **mod 维度传送** — 暮色森林的传送门可能需要额外处理（bot 可能无法自动进入暮色维度）。
3. **自定义实体** — 暮色森林的 boss 等自定义实体在 bot 视角可能显示为 unknown entity。
