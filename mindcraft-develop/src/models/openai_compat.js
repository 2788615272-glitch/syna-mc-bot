/**
 * 通用 OpenAI 兼容模型适配器
 * 
 * 从 model_registry.json 读取所有注册的模型提供商，为每个提供商动态生成一个类。
 * 每个类都有 static prefix 属性，会被 _model_map.js 自动发现并注册。
 * 
 * 添加新模型只需：
 * 1. 在 model_registry.json 加一条
 * 2. 在 keys.json 填对应 API key
 * 3. 在 profile 里设置 "api": "xxx"
 */

import OpenAIApi from 'openai';
import { getKey } from '../utils/keys.js';
import { strictFormat } from '../utils/text.js';
import { readFileSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Load registry
const registry = JSON.parse(
    readFileSync(path.join(__dirname, 'model_registry.json'), 'utf8')
);

/**
 * Factory: creates an OpenAI-compatible model class for a given registry entry.
 */
function createOpenAICompatClass(apiName, config) {
    const logTag = `[${apiName}]`;

    class OpenAICompat {
        constructor(model_name, url, params) {
            this.model_name = model_name || config.defaultModel;
            this.params = params || config.defaultParams || {};

            const apiKey = getKey(config.apiKeyName);
            if (!apiKey) {
                console.warn(`${logTag} Warning: ${config.apiKeyName} not found in keys.json`);
            }

            this.openai = new OpenAIApi({
                baseURL: url || config.baseURL,
                apiKey: apiKey || 'missing-key'
            });

            this.thinking = config.thinking || { type: 'disabled' };
        }

        async sendRequest(turns, systemMessage, stop_seq = '***') {
            let messages = [{ role: 'system', content: systemMessage }].concat(turns);
            messages = strictFormat(messages);
            const inputChars = messages.reduce((sum, m) => sum + String(m.content || '').length, 0);
            const approxInputTokens = Math.ceil(inputChars / 3);

            const pack = {
                model: this.model_name,
                messages,
                stop: stop_seq,
                thinking: this.thinking,
                ...(this.params || {})
            };

            let res = null;
            try {
                const startedAt = Date.now();
                console.log(`${logTag} Awaiting response from ${this.model_name}; messages=${messages.length}, input_chars=${inputChars}, approx_input_tokens=${approxInputTokens}`);
                const completion = await this.openai.chat.completions.create(pack);
                if (completion.choices[0].finish_reason === 'length')
                    throw new Error('Context length exceeded');
                const elapsedMs = Date.now() - startedAt;
                const usage = completion.usage ? `, usage=${JSON.stringify(completion.usage)}` : '';
                console.log(`${logTag} Received in ${elapsedMs}ms${usage}.`);
                res = completion.choices[0].message.content;
            } catch (err) {
                if ((err.message === 'Context length exceeded' || err.code === 'context_length_exceeded') && turns.length > 1) {
                    console.log('Context length exceeded, trying again with shorter context.');
                    return await this.sendRequest(turns.slice(1), systemMessage, stop_seq);
                }
                console.log(err);
                res = 'My brain disconnected, try again.';
            }

            return res;
        }

        /**
         * Streaming version: calls onSentence(partialText) each time a sentence boundary is detected.
         * Returns the full concatenated response text.
         */
        async sendRequestStreaming(turns, systemMessage, stop_seq = '***', onSentence = null) {
            let messages = [{ role: 'system', content: systemMessage }].concat(turns);
            messages = strictFormat(messages);
            const inputChars = messages.reduce((sum, m) => sum + String(m.content || '').length, 0);

            const pack = {
                model: this.model_name,
                messages,
                stop: stop_seq,
                stream: true,
                thinking: this.thinking,
                ...(this.params || {})
            };

            let fullText = '';
            let buffer = '';
            const sentenceEnd = /[\u3002\uFF01\uFF1F\uFF1B;\uFF0C,\u3001.!?\n]/;
            let commandDetected = false;

            try {
                const startedAt = Date.now();
                console.log(`${logTag}[Stream] Starting stream from ${this.model_name}; messages=${messages.length}, input_chars=${inputChars}`);
                const stream = await this.openai.chat.completions.create(pack);

                for await (const chunk of stream) {
                    const delta = chunk.choices?.[0]?.delta?.content;
                    if (!delta) continue;

                    fullText += delta;
                    buffer += delta;

                    if (onSentence && !commandDetected) {
                        let idx;
                        while ((idx = buffer.search(sentenceEnd)) !== -1) {
                            const sentence = buffer.slice(0, idx + 1).trim();
                            buffer = buffer.slice(idx + 1);
                            if (sentence) {
                                const cmdMatch = sentence.match(/!\w+\s*\(/);
                                if (cmdMatch) {
                                    const beforeCmd = sentence.substring(0, cmdMatch.index).trim();
                                    if (beforeCmd) onSentence(beforeCmd);
                                    commandDetected = true;
                                    break;
                                }
                                onSentence(sentence);
                            }
                        }
                    }
                }

                // Flush remaining buffer
                if (onSentence && !commandDetected && buffer.trim()) {
                    const cmdMatch = buffer.match(/!\w+\s*\(/);
                    if (cmdMatch) {
                        const beforeCmd = buffer.substring(0, cmdMatch.index).trim();
                        if (beforeCmd) onSentence(beforeCmd);
                    } else {
                        onSentence(buffer.trim());
                    }
                }

                const elapsedMs = Date.now() - startedAt;
                console.log(`${logTag}[Stream] Completed in ${elapsedMs}ms, total_chars=${fullText.length}`);
            } catch (err) {
                if ((err.message === 'Context length exceeded' || err.code === 'context_length_exceeded') && turns.length > 1) {
                    console.log('Context length exceeded in stream, trying with shorter context.');
                    return await this.sendRequestStreaming(turns.slice(1), systemMessage, stop_seq, onSentence);
                }
                console.log(err);
                fullText = fullText || 'My brain disconnected, try again.';
            }

            return fullText;
        }

        async sendVisionRequest(messages, systemPrompt, imageBuffer) {
            const base64 = imageBuffer.toString('base64');
            const dataUrl = `data:image/png;base64,${base64}`;

            const visionMessages = [
                { role: 'system', content: systemPrompt },
                ...messages,
                {
                    role: 'user',
                    content: [
                        { type: 'image_url', image_url: { url: dataUrl } },
                        { type: 'text', text: '请描述这张截图中你看到的内容。' }
                    ]
                }
            ];

            const pack = {
                model: this.model_name,
                messages: visionMessages,
                thinking: this.thinking,
                ...(this.params || {})
            };

            try {
                console.log(`${logTag} Vision request to ${this.model_name}`);
                const completion = await this.openai.chat.completions.create(pack);
                return completion.choices[0].message.content;
            } catch (err) {
                console.log(`${logTag} Vision request failed:`, err.message);
                return 'Vision request failed: ' + err.message;
            }
        }

        async embed(text) {
            // Lightweight local fallback embedding
            const dim = 384;
            const vector = Array(dim).fill(0);
            const input = String(text || '').toLowerCase();

            for (let i = 0; i < input.length; i++) {
                const code = input.charCodeAt(i);
                vector[(code + i * 31) % dim] += 1;
                if (i + 1 < input.length) {
                    const next = input.charCodeAt(i + 1);
                    vector[(code * 17 + next * 13 + i) % dim] += 0.5;
                }
            }

            const norm = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0)) || 1;
            return vector.map(value => value / norm);
        }
    }

    // Set the static prefix so _model_map.js can discover it
    OpenAICompat.prefix = apiName;

    // Give the class a meaningful name for debugging
    Object.defineProperty(OpenAICompat, 'name', { value: `OpenAICompat_${apiName}` });

    return OpenAICompat;
}

// Export a class for each registry entry (skip _comment key)
const exportedClasses = {};
for (const [apiName, config] of Object.entries(registry)) {
    if (apiName.startsWith('_')) continue;
    exportedClasses[apiName] = createOpenAICompatClass(apiName, config);
}

// Named exports for each provider
export const { moonshot, deepseek } = exportedClasses;

// Also export all as default for programmatic access
export class custom extends (exportedClasses.custom || createOpenAICompatClass('custom', registry.custom || {
    description: 'Custom OpenAI-compatible endpoint',
    baseURL: 'http://127.0.0.1:11434/v1',
    apiKeyName: 'CUSTOM_OPENAI_API_KEY',
    defaultModel: 'custom-model',
    defaultParams: { temperature: 0.6 },
    thinking: { type: 'disabled' }
})) {}
custom.prefix = 'custom';

// Also export all as default for programmatic access
export default { ...exportedClasses, custom };
