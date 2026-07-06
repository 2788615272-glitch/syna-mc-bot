import { Vec3 } from 'vec3';
import fs from 'fs';

let sharpLoader = null;

async function getSharp() {
    if (sharpLoader === null) {
        sharpLoader = import('sharp')
            .then(module => module.default || module)
            .catch(() => false);
    }
    return await sharpLoader;
}

export class VisionInterpreter {
    constructor(agent, allow_vision) {
        this.agent = agent;
        this.allow_vision = allow_vision;
        this.fp = './bots/'+agent.name+'/screenshots/';
        this.camera = null;
        this._cameraFailed = false; // true if camera init failed (e.g. canvas.node missing)
    }

    async getCamera() {
        if (this._cameraFailed) return null;
        if (!this.camera) {
            try {
                const { Camera } = await import('./camera.js');
                this.camera = new Camera(this.agent.bot, this.fp);
            } catch (err) {
                this._cameraFailed = true;
                console.warn(`[Vision] Camera unavailable: ${err.message}. Vision will use text-only fallback.`);
                return null;
            }
        }
        return this.camera;
    }

    async lookAtPlayer(player_name, direction) {
        if (!this.allow_vision || !this.agent.prompter.vision_model.sendVisionRequest) {
            return "Vision is disabled. Use other methods to describe the environment.";
        }
        const bot = this.agent.bot;
        const player = bot.players[player_name]?.entity;
        if (!player) {
            return `Could not find player ${player_name}`;
        }

        let result = "";
        if (direction === 'with') {
            await bot.look(player.yaw, player.pitch);
            result = `Looking in the same direction as ${player_name}\n`;
        } else {
            await bot.lookAt(new Vec3(player.position.x, player.position.y + player.height, player.position.z));
            result = `Looking at player ${player_name}\n`;
        }

        // Try to capture screenshot; if camera is unavailable, fall back to block info only
        const camera = await this.getCamera();
        if (!camera) {
            const blockInfo = this.getCenterBlockInfo();
            return result + `(Screenshot unavailable — canvas module missing)\n${blockInfo}`;
        }

        const filename = await camera.capture();
        return result + `Image analysis: "${await this.analyzeImage(filename)}"`;
    }

    async lookAtPosition(x, y, z) {
        if (!this.allow_vision || !this.agent.prompter.vision_model.sendVisionRequest) {
            return "Vision is disabled. Use other methods to describe the environment.";
        }
        const bot = this.agent.bot;
        await bot.lookAt(new Vec3(x, y + 2, z));
        let result = `Looking at coordinate ${x}, ${y}, ${z}\n`;

        // Try to capture screenshot; if camera is unavailable, fall back to block info only
        const camera = await this.getCamera();
        if (!camera) {
            const blockInfo = this.getCenterBlockInfo();
            return result + `(Screenshot unavailable — canvas module missing)\n${blockInfo}`;
        }

        const filename = await camera.capture();
        return result + `Image analysis: "${await this.analyzeImage(filename)}"`;
    }

    getCenterBlockInfo() {
        const bot = this.agent.bot;
        const maxDistance = 128; // Maximum distance to check for blocks
        const targetBlock = bot.blockAtCursor(maxDistance);
        
        if (targetBlock) {
            return `Block at center view: ${targetBlock.name} at (${targetBlock.position.x}, ${targetBlock.position.y}, ${targetBlock.position.z})`;
        } else {
            return "No block in center view";
        }
    }

    async analyzeImage(filename) {
        try {
            const rawBuffer = fs.readFileSync(`${this.fp}/${filename}.jpg`);
            let imageBuffer = rawBuffer;
            const sharp = await getSharp();
            if (sharp) {
                imageBuffer = await sharp(rawBuffer)
                    .resize(480, 270, { fit: 'inside' })
                    .jpeg({ quality: 50 })
                    .toBuffer();
                console.log(`[Vision] Resized screenshot: ${rawBuffer.length} -> ${imageBuffer.length} bytes`);
            } else {
                console.warn('[Vision] sharp package unavailable; using raw screenshot buffer.');
            }

            const messages = this.agent.history.getHistory();

            const blockInfo = this.getCenterBlockInfo();
            const result = await this.agent.prompter.promptVision(messages, imageBuffer);
            return result + `\n${blockInfo}`;

        } catch (error) {
            console.warn('Error reading image:', error);
            return `Error reading image: ${error.message}`;
        }
    }
}
