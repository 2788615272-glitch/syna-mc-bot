# SynaBridge Forge Mod (1.20.1) MVP

这是一个**可继续扩展成世界内 AI 身体**的 Forge 模组骨架。

当前版本先做的是最重要的第一步：

- 运行在 Minecraft / Forge **引擎内部**
- 暴露一个本地 HTTP 桥接服务
- 提供真实玩家 / 世界状态
- 支持最基础的外部命令调用
- 支持“进入世界 / 离开世界”公告效果

## 当前已实现的能力

- `GET /health`
- `GET /state`
- `POST /command`
  - `say`
  - `announce_join`
  - `announce_leave`
  - `bind_first_player`

## 为什么先做桥接，不直接做完整 NPC

因为你当前最大的收益点是：

1. 先把**真实世界数据**稳定拿到
2. 先让外部 `brain_vtb.py / mc_bridge_server.py` 能稳定连进来
3. 再把动作从 `mineflayer` 逐步迁移成模组内技能

这一步是“模组化路线”的最小可行起点。

## 目录说明

- `build.gradle` / `settings.gradle` / `gradle.properties`：Forge 工程配置
- `src/main/java/.../SynaBridgeMod.java`：模组入口
- `src/main/java/.../BridgeHttpServer.java`：本地 HTTP 桥
- `src/main/java/.../BridgeState.java`：状态缓存
- `src/main/resources/META-INF/mods.toml`：Forge 模组元数据

## API 示例

### 1) 健康检查

```bash
curl http://127.0.0.1:8765/health
```

### 2) 获取状态

```bash
curl http://127.0.0.1:8765/state
```

### 3) 发命令

```bash
curl -X POST http://127.0.0.1:8765/command ^
  -H "Content-Type: application/json" ^
  -d "{\"type\":\"say\",\"text\":\"你好，我是 Syna\"}"
```

## 下一步建议

下一阶段可以继续加：

- `follow(player)`
- `goto(x,y,z)`
- `look_at(player)`
- `attack(target)`
- 自定义 Syna 实体
- 相机附着到 Syna 的第一人称 / 伪第一人称视角

## 重要说明

你这台机器当前**没有 Java / Gradle 环境**，所以我现在可以先把模组源码写出来，但**不能在本机直接编译出 jar**。

也就是说：

- **我已经能把模组工程写好**
- 但要真正构建成可放进 `mods/` 的 jar，仍然需要你后续补：
  - JDK 17
  - Gradle（或使用 Gradle Wrapper）
  - Forge MDK 1.20.1 对应环境

如果你愿意，下一步我可以继续把这个骨架往前推进成：

1. **自定义 Syna 实体版**
2. **带 follow/goto 的技能版**
3. **直接对接你现有 Python brain 的协议版**