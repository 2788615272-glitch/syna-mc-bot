# Syna 功能测试命令

直接在游戏聊天栏输入，不需要开启作弊。测试权限仅属于当前绑定玩家。

先输入 `syna test reset` 清理上一轮状态；输入 `syna test list` 可在游戏内查看分类。

## 现身与物品

- `syna test spawn`：在背后或身边的安全空间现身，带声音和粒子；平静闲置约 16 秒后自动消失。
- `syna test vanish`：立即带声音和粒子消失。
- `syna test gift minecraft:iron_ingot 4`：现身并给予 4 个铁锭；物品 ID 和数量可替换，作弊/调试物品仍会拒绝。
- `syna test lookbehind`：测试回头恶作剧。
- `syna test omen`：触发完整的首次“别回头”预兆、屏幕音效和观察者事件。
- `syna test tunnel`：在附近安全空间触发洞穴式显现；玩家看见后闪光消失。

## 轻度随机恐怖

- `syna test steps`：背后脚步声。
- `syna test knock`：远处敲击声。
- `syna test breath`：洞穴呼吸/环境声。
- `syna test darkness`：约两秒黑暗。
- `syna test hit`：一次受控轻击；同一故事周期可能受重复限制，先用 `reset`。

声音事件只证明服务器发出了声音，无法证明客户端实际听见，因此 Syna 默认不会为它们配台词。

## 导演实体事件

- `syna test watcher`
- `syna test stalker`
- `syna test ambush`
- `syna test enforcer`

这些命令走真实事件队列。聊天回执 `accepted=true` 只表示已排入；用 `syna test status` 查看 `horrorEvents`，事件应经历 scheduled、active/exposed、completed 或 aborted。

## 最终周期与规则游戏

- `syna test warning`：警告阶段。
- `syna test countdown`：倒计时阶段。
- `syna test hunt`：追杀阶段。
- `syna test calm`：恢复平静。
- `syna test game_block`：投入指定物品的规则游戏。
- `syna test game_kill`：击杀指定生物的规则游戏。
- `syna test boredom 0` 到 `syna test boredom 100`：直接设置无聊值。
- `syna test final`：立即从满无聊值启动最终周期。
- `syna test rules off`：关闭最终周期和日常随机恐怖；运行中的最终周期会结束。
- `syna test rules on`：重新开启两类导演事件。

也可使用原版规则：`/gamerule synaFinalCycle false` 和 `/gamerule synaHorrorEvents false`。

## 观察、剧情与其他功能

- `syna test observe diamond|debris|emerald|nether|end|dragon|wither|warden`：模拟一条服务器确认的玩家行为，测试主动评论。
- `syna test story observe|arrival|footsteps|trace|watcher|disappear|boundary|silence|warning|stalker|touch|reveal|ambush|enforcer|pursuit|locked_door|judgment|mercy|ending`：强制指定剧情场景。
- `syna test fx on|off`：开关恐怖视觉/声音效果。
- `syna test status`：查看最近事件、剧情、恐怖阶段和无聊值回执。
- `syna test cleanup`：清除当前 Syna 和导演实体。
- `syna test reset`：清除故事重复锁、导演实体、当前 Syna，并把无聊值恢复到初始值。

原有任务功能继续使用：`syna follow`、`syna wood 3`、`syna stone 3`、`syna craft minecraft:oak_planks 4`、`syna goto x y z`、`syna stop`、`syna status`。
