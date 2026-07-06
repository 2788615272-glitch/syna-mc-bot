const settings = {
    "minecraft_version": "1.21.1", // NeoForge 21.1.230（含暮色森林等 mod）。1.20.1 旧整合包用 FML3 分支兜底；mcdata.js 会按版本号自动切握手路径
    "host": "127.0.0.1", // or "localhost", "your.ip.address.here"
    "port": 25565, // 哥的正版 PCL 单人游戏「Zombie Invade 100 Days」开局域网时手动锁定的端口；自动扫描(-1)会误命中 8081/8765/8766 等 HTTP 端口导致 socket 收到 -1 崩溃，所以这里写死。
    "auth": "offline", // or "microsoft"

    // the mindserver manages all agents and hosts the UI
    "mindserver_port": 8081,
    "auto_open_ui": true, // opens UI in browser on startup
    
    "base_profile": "assistant", // survival, assistant, creative, or god_mode
    // ═══════════════════════════════════════════════════════════════
    // ★★★ 切换模型：去 profiles/syna.json 顶部改 model 字段 ★★★
    // 可选：api=moonshot model=kimi-k2.5 | api=deepseek model=deepseek-v4-flash
    // 视觉模型固定 Kimi，不用动
    // ═══════════════════════════════════════════════════════════════
    "profiles": [
        "./profiles/syna.json",
    ],

    "load_memory": false, // load memory from previous session
    "init_message": "请用简短自然的中文打招呼，并说明你叫 syna。", // sends to all on spawn
    "only_chat_with": [], // users that the bots listen to and send general messages to. if empty it will chat publicly
    "chat_routing": {
        "allow_public_without_mention": false,
        "log_ignored": true
    }, // public chat only wakes the bot when addressed; whisper/voice/system still route through.

    "speak": false,
    // allows all bots to speak through text-to-speech. 
    // specify speech model inside each profile with format: {provider}/{model}/{voice}.
    // if set to "system" it will use basic system text-to-speech. 
    // Works on windows and mac, but linux requires you to install the espeak package through your package manager eg: `apt install espeak` `pacman -S espeak`.

    "chat_ingame": true, // bot responses are shown in minecraft chat
    "language": "en", // 设为 en 跳过 Google Translate（我们 prompt 和回复都是中文，不需要翻译层，避免被墙超时）
    "render_bot_view": false, // show bot's view in browser at localhost:3000, 3001...

    "allow_insecure_coding": true, // allows newAction command and model can write/run code on your computer. enable at own risk
    "allow_vision": true, // allows vision model to interpret screenshots as inputs
    "blocked_actions" : ["!checkBlueprint", "!checkBlueprintLevel", "!getBlueprint", "!getBlueprintLevel"] , // commands to disable and remove from docs. Ex: ["!setMode"]
    "code_timeout_mins": -1, // minutes code is allowed to run. -1 for no timeout
    "relevant_docs_count": 5, // number of relevant code function docs to select for prompting. -1 for all

    "max_messages": 80, // max recent messages before compressing older turns into memory.
    "summary_chunk_size": 12, // message-count rollover chunk size.
    "max_context_tokens": 180000, // estimated input budget before rolling older turns into memory.
    "context_compress_target_tokens": 120000, // after token compression, keep roughly this many recent-turn tokens.
    "context_recent_keep_messages": 24, // never compress the most recent conversational turns unless unavoidable.
    "prompt_token_reserve": 12000, // rough reserve for system prompt, command docs, stats, inventory, and reply.
    "memory_summary_chars": 6000, // compressed long-term narrative memory budget after history rolls over.
    "num_examples": 1, // number of examples to give to the model. Lower saves tokens without reducing task/history memory.
    "max_commands": -1, // max number of commands that can be used in consecutive responses. -1 for no limit
    "show_command_syntax": "retrieval", // "full", "shortened", "retrieval", or "none"
    "command_docs_max_categories": 4, // retrieval mode: max relevant command categories to include each prompt
    "narrate_behavior": true, // chat simple automatic actions ('正在捡东西。')
    "chat_bot_messages": true, // publicly chat messages to other bots

    "spawn_timeout": 30, // num seconds allowed for the bot to spawn before throwing error. Increase when spawning takes a while.
    "block_place_delay": 0, // delay between placing blocks (ms) if using newAction. helps avoid bot being kicked by anti-cheat mechanisms on servers.
    // 历史经验：throttle=true 会在「被怪击退」瞬间吞掉关键 position 包，
    // 反而触发服务端 invalid_player_movement 踢人（详见 bots/<name>/被攻击掉线日志）。
    // 默认关掉。只有在确认服务端是严格 anti-cheat 且没有击退场景时才打开。
    "position_packet_throttle": false,
    "position_packet_throttle_ms": 50,
    "agent_restart_min_lifetime_ms": 3000, // if the agent dies sooner than this, delay and retry instead of giving up immediately.
    "agent_restart_delay_ms": 5000,
    "agent_max_quick_restarts": 6,
    "syna_probe": {
        "enabled": true,
        "affect_actions": false,
        "base_url": "http://127.0.0.1:8765",
        "timeout_ms": 5000,
        "log_on_spawn": true
    }, // optional local Forge mod probe. affect_actions=false keeps it observational only; Mineflayer remains the main controller.
    "horror_awareness": {
        "enabled": true,
        "log": true
    },
    "syna_proactivity": {
        "enabled": true,
        "log": true,
        "interval_ms": 15000,
        "min_gap_ms": 90000,
        "quiet_after_user_ms": 45000,
        "player_radius": 32,
        "chance": 0.55
    },
    "syna_voice": {
        "enabled": true,
        "base_url": "http://127.0.0.1:8766",
        "timeout_ms": 800,
        "log_voice_text": true,
        "log_errors": true,
        "show_user_voice_in_chat": true,
        "system_status_tts": true
    }, // local Syna TTS bridge. Start with run_syna_voice_server_cn.ps1; if offline, gameplay continues normally.

    "disconnect_probe": {
        "enabled": true,
        "ring_seconds": 60,
        "frame_interval_ms": 250,
        "dump_packets": true,
        "max_packets": 200,
        "survivor_hp_drop": 4
    }, // 60s 环形 buffer, kicked/end 时 dump bots/{name}/被攻击掉线日志/最新.json + 最新_摘要.txt
  
    "log_all_prompts": false, // log ALL prompts to file
};

export default settings;

