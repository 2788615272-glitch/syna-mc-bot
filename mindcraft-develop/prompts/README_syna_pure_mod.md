# Syna 纯 Mod 提示词说明

实际编辑文件是 `syna_pure_mod.json`。保存后需要重启“纯 Mod Core”；不需要重新编译或替换 Mod JAR。

## 每段提示词的用途

- `settings`：最终输出的硬限制。`max_chars` 是最大字符数，`max_sentences` 是最大句数；`silence_token` 表示主动选择不说话；`fallback_reply` 只在模型失败或输出违规时使用，留空表示沉默。
- `persona`：所有语言生成共享的人格底色。这里适合写 Syna 是谁、怎么看玩家、说话习惯和绝不该做的事，不适合写具体事件台词。
- `intent_decision`：决定是否执行真实动作，例如现身、给物品、离开或开始游戏。它只输出 JSON，不负责说话。即使这里写了很大的权限，最终动作仍要经过 Mod 验证。
- `normal_reply`：普通聊天的措辞。玩家没有触发真实动作时主要使用这一段。
- `action_receipt_reply`：现身、给物品、开关效果、开始游戏等动作执行之后使用。它能看到真实回执，因此只有这里可以确认动作成功或失败。
- `proactive_event_reply`：脚步、敲击、黑暗、观察者、跟踪者等主动事件触发时使用。允许选择沉默，避免每个事件都配一句尴尬台词。
- `observation_reply`：玩家发现钻石、进入维度、击杀重要生物等已确认行为发生后使用。
- `horror_reply`：警告、倒计时、追杀等阶段的附加约束。它会和当前类型的提示词一起使用，不单独生成台词。

## 运行时提供的上下文

这些名字不需要在 JSON 中手动替换。Core 会在每次请求中追加对应数据：

- `PLAYER_TEXT`：玩家刚说的话。
- `AUTHORITATIVE_CONTEXT`：Mod 确认的玩家、Syna、剧情、无聊值、权限和事件状态。
- `DISCLOSURE_FACTS`：本轮允许说出的全知信息，例如玩家主动询问的背包数量，或玩家否认的近期行为。为空时不应主动泄露。
- `ACTION_RECEIPT`：动作的真实执行结果，包含是否接受、是否完成和结果原因。
- `SCENE_FACTS`：主动恐怖事件的类型、无聊值和已确认状态。
- `SCENE_GOAL`：代码给出的场景目标，只描述“想达到什么效果”，不是固定台词。
- `OBSERVATION_FACTS`：玩家刚完成的已确认行为。
- `HORROR_FACTS`：当前恐怖阶段、形态、挑战和倒计时等规则事实。

JSON 内目前只有 `{{silence_token}}` 是模板变量，它会替换成 `settings.silence_token`。其他大写名字是运行时上下文标题，直接在提示词里引用即可。

## 哪些内容不能只靠提示词修改

物品是否属于作弊物品、一次能给多少、动作是否真的完成、事件状态和挑战规则都由 Mod/Core 校验。提示词控制 Syna 的判断倾向与表达方式，不会绕过这些事实限制。这样可以自由改人格，同时避免“嘴上说给了，实际没给”的幻觉。

JSON 必须保留双引号、逗号和括号。修改后可在 `mindcraft-develop` 目录运行：

```powershell
node -e "JSON.parse(require('fs').readFileSync('prompts/syna_pure_mod.json','utf8')); console.log('ok')"
```
