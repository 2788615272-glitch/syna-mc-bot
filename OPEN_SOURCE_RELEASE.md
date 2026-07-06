# Syna Open Source Release Notes

This repository is intended to be published as a user-facing Syna package:

1. Download the project.
2. Open the Syna launcher.
3. Enter model and voice keys locally.
4. Choose the Minecraft `mods` folder.
5. Click install/update.
6. Start Syna and use the full bot, mod, TTS, and ASR workflow.

## What Must Be Public

- `mindcraft-develop/` source code.
- `mindcraft-develop/package.json` and `mindcraft-develop/package-lock.json`.
- `mindcraft-develop/keys.example.json`.
- `mindcraft-develop/launcher/SynaLauncherWpf.cs`.
- A current compiled launcher in the release package, not necessarily tracked in Git.
- `syna_mod/` source code.
- `syna_mod/built-jars/synabridge-0.1.0.jar` for users who do not want to build the mod.

## What Must Stay Local

Never commit these:

- `mindcraft-develop/keys.json`
- `mindcraft-develop/Syna_本机接入信息_含Key.txt`
- `mindcraft-develop/control_config.json`
- `mindcraft-develop/launcher/launcher_config.json`
- `mindcraft-develop/launcher/voice_config.json`
- `mindcraft-develop/bots/`
- `mindcraft-develop/logs/`
- `mindcraft-develop/node_modules/`
- `syna_mod/build/`
- `syna_mod/.gradle/`
- compiled launcher test builds such as `launcher/*.exe` and `launcher/*.TMP`

## Current Launcher Capability

The WPF launcher already supports:

- editing model provider, base URL, model name, and API key;
- saving keys into local `keys.json`;
- editing Volcano voice and ASR settings;
- choosing a Minecraft/pack `mods` folder;
- installing `synabridge-0.1.0.jar` into that folder;
- testing the Minecraft LAN connection;
- starting Mindcraft, TTS, and ASR together.

The main missing release-grade piece is dependency bootstrap. A fresh user still needs Node.js and `npm install` to have completed before the launcher can run `main.js` and `launcher/config_bridge.mjs` reliably.

## Recommended Release Flow

Before publishing:

```powershell
git init -b main
git add -n .
git status --short
```

Check the dry run carefully. None of the local-only files above should appear.

Then:

```powershell
git add .
git commit -m "Initial open-source release"
```

Create the GitHub repository and push:

```powershell
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```

For a downloadable release, attach a zip that includes:

- `mindcraft-develop/`
- `syna_mod/built-jars/synabridge-0.1.0.jar`
- the compiled launcher executable
- no `keys.json`, no logs, no bot memory, no local config

## Next Engineering Task

Add an "Install dependencies" step to the launcher:

- detect whether Node.js is available;
- detect whether `mindcraft-develop/node_modules` exists;
- run `npm install` from `mindcraft-develop`;
- show progress in the launcher log;
- block `Start Syna` until dependencies are ready;
- optionally download or guide installation of Node.js when missing.

After that, the launcher's first-run path can be:

1. Install dependencies.
2. Save model/voice keys.
3. Install Syna Mod.
4. Test Minecraft connection.
5. Start Syna.
