/**
 * Schematic Importer
 *
 * Parses .litematic (Litematica) and .schem (WorldEdit/Sponge) files into
 * the flat block list format used by ModBlueprintClient.uploadRaw().
 *
 * Also provides a local blueprint library: scan a directory for schematic
 * files and list them for the AI to pick from.
 *
 * Supported formats:
 *   - .litematic (Litematica NBT, gzipped)
 *   - .schem     (Sponge Schematic v2/v3, gzipped NBT)
 *   - .nbt       (Vanilla structure block format)
 *   - .json      (Our own palette+layers format, like cabin.json)
 *
 * Dependencies: prismarine-nbt (already in mindcraft's node_modules via
 * mineflayer/minecraft-protocol).
 */

import fs from 'fs';
import path from 'path';
import { promisify } from 'util';
import zlib from 'zlib';

const gunzip = promisify(zlib.gunzip);

// prismarine-nbt should be available through mineflayer's dependency tree
let nbt;
try {
    nbt = await import('prismarine-nbt');
} catch {
    // Fallback: try require
    try {
        const { createRequire } = await import('module');
        const require = createRequire(import.meta.url);
        nbt = require('prismarine-nbt');
    } catch {
        nbt = null;
    }
}

/**
 * Parse a .schem file (Sponge Schematic format v2/v3).
 * Returns { width, height, length, blocks: [{dx, dy, dz, name}] }
 */
async function parseSchem(buffer) {
    if (!nbt) throw new Error('prismarine-nbt not available');

    const data = await decompressNBT(buffer);
    const parsed = nbt.parseUncompressed(data);
    const root = parsed.parsed?.value || parsed.value;

    // Sponge v2/v3: root is "Schematic" compound
    const schematic = root.Schematic?.value || root;

    const width = getInt(schematic.Width);
    const height = getInt(schematic.Height);
    const length = getInt(schematic.Length);

    // Palette: maps "minecraft:oak_planks[axis=y]" -> varint index
    const paletteTag = schematic.Palette?.value || {};
    const reversePalette = {}; // index -> blockName
    for (const [blockStr, idxTag] of Object.entries(paletteTag)) {
        const idx = getInt(idxTag);
        // Strip properties for simplicity: "minecraft:oak_log[axis=y]" -> "oak_log"
        const name = stripBlockName(blockStr);
        reversePalette[idx] = name;
    }

    // BlockData: varint-encoded array of palette indices
    // prismarine-nbt may return Buffer, Int8Array, or plain array for ByteArray tags
    let blockData = schematic.BlockData?.value || [];
    if (Buffer.isBuffer(blockData) || ArrayBuffer.isView(blockData)) {
        // Convert to regular array so indexing works consistently
        blockData = Array.from(blockData);
    }
    if (!blockData || blockData.length === 0) {
        console.warn('[SchematicImporter] BlockData is empty or unreadable! Type:', typeof schematic.BlockData?.value, 'Tag:', JSON.stringify(schematic.BlockData)?.slice(0, 200));
        return { width, height, length, blocks: [] };
    }
    console.log(`[SchematicImporter] .schem BlockData: ${blockData.length} bytes, palette entries: ${Object.keys(paletteTag).length}, dimensions: ${width}x${height}x${length}`);
    const blocks = [];

    let i = 0;
    for (let y = 0; y < height; y++) {
        for (let z = 0; z < length; z++) {
            for (let x = 0; x < width; x++) {
                // Read varint
                let value = 0;
                let shift = 0;
                while (true) {
                    if (i >= blockData.length) break;
                    const b = blockData[i] & 0xFF;
                    i++;
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) === 0) break;
                    shift += 7;
                }

                const name = reversePalette[value] || 'air';
                if (name !== 'air') {
                    blocks.push({ dx: x, dy: y, dz: z, name });
                }
            }
        }
    }

    return { width, height, length, blocks };
}

/**
 * Parse a .litematic file (Litematica format).
 * These use a different NBT structure with regions and bit-packed block arrays.
 */
async function parseLitematic(buffer) {
    if (!nbt) throw new Error('prismarine-nbt not available');

    const data = await decompressNBT(buffer);
    const parsed = nbt.parseUncompressed(data);
    const root = parsed.parsed?.value || parsed.value;

    const regions = root.Regions?.value || {};
    const blocks = [];

    for (const [regionName, regionTag] of Object.entries(regions)) {
        const region = regionTag.value || regionTag;

        const size = region.Size?.value || {};
        const pos = region.Position?.value || {};
        const rx = getInt(pos.x) || 0;
        const ry = getInt(pos.y) || 0;
        const rz = getInt(pos.z) || 0;
        const sx = Math.abs(getInt(size.x) || 0);
        const sy = Math.abs(getInt(size.y) || 0);
        const sz = Math.abs(getInt(size.z) || 0);

        // Palette
        const palette = region.BlockStatePalette?.value?.value || [];
        const paletteNames = palette.map(entry => {
            const name = entry.Name?.value || 'minecraft:air';
            return stripBlockName(name);
        });

        // Block states: long array, bit-packed
        const blockStates = region.BlockStates?.value || [];
        if (blockStates.length === 0) continue;

        const totalBlocks = sx * sy * sz;
        const bitsPerEntry = Math.max(2, Math.ceil(Math.log2(paletteNames.length)));
        const mask = (1n << BigInt(bitsPerEntry)) - 1n;

        // Convert to BigInt array for bit manipulation
        const longArray = blockStates.map(v => {
            if (typeof v === 'bigint') return v;
            // prismarine-nbt may return [number, number] for longs
            if (Array.isArray(v)) {
                return (BigInt(v[0] >>> 0) << 32n) | BigInt(v[1] >>> 0);
            }
            return BigInt(v);
        });

        let blockIdx = 0;
        for (let y = 0; y < sy; y++) {
            for (let z = 0; z < sz; z++) {
                for (let x = 0; x < sx; x++) {
                    if (blockIdx >= totalBlocks) break;

                    const bitPos = BigInt(blockIdx) * BigInt(bitsPerEntry);
                    const longIdx = Number(bitPos / 64n);
                    const bitOffset = bitPos % 64n;

                    let paletteIdx;
                    if (longIdx < longArray.length) {
                        let val = (longArray[longIdx] >> bitOffset) & mask;
                        // Handle spanning two longs
                        if (bitOffset + BigInt(bitsPerEntry) > 64n && longIdx + 1 < longArray.length) {
                            const overflow = bitOffset + BigInt(bitsPerEntry) - 64n;
                            val |= (longArray[longIdx + 1] & ((1n << overflow) - 1n)) << (BigInt(bitsPerEntry) - overflow);
                        }
                        paletteIdx = Number(val);
                    } else {
                        paletteIdx = 0;
                    }

                    const name = paletteNames[paletteIdx] || 'air';
                    if (name !== 'air') {
                        blocks.push({
                            dx: rx + x,
                            dy: ry + y,
                            dz: rz + z,
                            name
                        });
                    }

                    blockIdx++;
                }
            }
        }
    }

    // Normalize: shift so minimum dx/dy/dz is 0
    if (blocks.length > 0) {
        const minX = Math.min(...blocks.map(b => b.dx));
        const minY = Math.min(...blocks.map(b => b.dy));
        const minZ = Math.min(...blocks.map(b => b.dz));
        for (const b of blocks) {
            b.dx -= minX;
            b.dy -= minY;
            b.dz -= minZ;
        }
    }

    return { blocks };
}

/**
 * Parse a vanilla .nbt structure file.
 */
async function parseStructureNBT(buffer) {
    if (!nbt) throw new Error('prismarine-nbt not available');

    const data = await decompressNBT(buffer);
    const parsed = nbt.parseUncompressed(data);
    const root = parsed.parsed?.value || parsed.value;

    const palette = root.palette?.value?.value || [];
    const paletteNames = palette.map(entry => {
        const name = entry.Name?.value || 'minecraft:air';
        return stripBlockName(name);
    });

    const blocksList = root.blocks?.value?.value || [];
    const blocks = [];

    for (const block of blocksList) {
        const pos = block.pos?.value?.value || [];
        const state = getInt(block.state);
        const name = paletteNames[state] || 'air';
        if (name !== 'air' && pos.length === 3) {
            blocks.push({
                dx: getInt(pos[0]),
                dy: getInt(pos[1]),
                dz: getInt(pos[2]),
                name
            });
        }
    }

    return { blocks };
}

/**
 * Parse our own JSON blueprint format (palette + layers).
 */
function parseJsonBlueprint(jsonStr) {
    const data = JSON.parse(jsonStr);
    const { palette, layers } = data;
    const blocks = [];

    for (const layer of layers) {
        const dy = layer.y;
        for (let dz = 0; dz < layer.grid.length; dz++) {
            const row = layer.grid[dz];
            for (let dx = 0; dx < row.length; dx++) {
                const sym = row[dx];
                const name = palette[sym] || 'air';
                if (name !== 'air' && sym !== '.') {
                    blocks.push({ dx, dy, dz, name });
                }
            }
        }
    }

    return { blocks, meta: { name: data.name, description: data.description, size: data.size } };
}

// ─── Utility ────────────────────────────────────────────────────────────────

async function decompressNBT(buffer) {
    // Try gunzip first (most schematics are gzipped)
    try {
        return await gunzip(buffer);
    } catch {
        // Might be uncompressed
        return buffer;
    }
}

function getInt(tag) {
    if (tag == null) return 0;
    if (typeof tag === 'number') return tag;
    if (typeof tag === 'object' && 'value' in tag) return Number(tag.value);
    return Number(tag) || 0;
}

function stripBlockName(fullName) {
    // "minecraft:oak_planks[axis=y]" -> "oak_planks"
    let name = fullName.replace(/\[.*\]$/, '');
    name = name.replace(/^minecraft:/, '');
    return name;
}

// ─── Blueprint Library ──────────────────────────────────────────────────────

const SUPPORTED_EXTENSIONS = ['.litematic', '.schem', '.nbt', '.json'];

/**
 * Scan a directory for schematic files and return a list of available blueprints.
 */
export function listBlueprintLibrary(libraryDir) {
    if (!fs.existsSync(libraryDir)) {
        fs.mkdirSync(libraryDir, { recursive: true });
        return [];
    }

    const entries = [];
    const files = fs.readdirSync(libraryDir);
    for (const file of files) {
        const ext = path.extname(file).toLowerCase();
        if (!SUPPORTED_EXTENSIONS.includes(ext)) continue;

        const filePath = path.join(libraryDir, file);
        const stat = fs.statSync(filePath);
        entries.push({
            name: path.basename(file, ext),
            file: file,
            format: ext.slice(1), // "litematic", "schem", "nbt", "json"
            size: stat.size,
            path: filePath,
        });
    }

    return entries;
}

/**
 * Import a schematic file and return the upload payload for ModBlueprintClient.
 *
 * @param {string} filePath - Path to the schematic file
 * @param {object} origin - {x, y, z} world position to place the blueprint
 * @param {string} [id] - Blueprint ID (defaults to filename)
 * @returns {object} Payload ready for ModBlueprintClient.uploadRaw()
 */
export async function importSchematic(filePath, origin, id = null) {
    const ext = path.extname(filePath).toLowerCase();
    const name = id || path.basename(filePath, ext);

    // Guard: origin 必须是有效数字坐标
    if (!origin || !Number.isFinite(origin.x) || !Number.isFinite(origin.y) || !Number.isFinite(origin.z)) {
        throw new Error(`importSchematic: origin 坐标无效 (got ${JSON.stringify(origin)}). 必须提供有效的 {x, y, z} 数字坐标。`);
    }

    let result;

    if (ext === '.json') {
        const content = fs.readFileSync(filePath, 'utf-8');
        result = parseJsonBlueprint(content);
    } else {
        const buffer = fs.readFileSync(filePath);

        switch (ext) {
            case '.schem':
                result = await parseSchem(buffer);
                break;
            case '.litematic':
                result = await parseLitematic(buffer);
                break;
            case '.nbt':
                result = await parseStructureNBT(buffer);
                break;
            default:
                throw new Error(`Unsupported format: ${ext}`);
        }
    }

    if (!result.blocks || result.blocks.length === 0) {
        throw new Error(`No blocks found in ${filePath}`);
    }

    console.log(`[SchematicImporter] Parsed ${filePath}: ${result.blocks.length} blocks`);

    return {
        id: name,
        ox: origin.x,
        oy: origin.y,
        oz: origin.z,
        mode: 'build',
        auto_clear: false,
        blocks: result.blocks,
    };
}

/**
 * Get the default blueprint library directory.
 */
export function getLibraryDir() {
    // Look relative to the project root
    const candidates = [
        path.resolve('./src/agent/library/blueprints'),
        path.resolve('./blueprints'),
    ];
    for (const dir of candidates) {
        if (fs.existsSync(dir)) return dir;
    }
    // Default: create in project
    const defaultDir = path.resolve('./src/agent/library/blueprints');
    fs.mkdirSync(defaultDir, { recursive: true });
    return defaultDir;
}

/**
 * High-level: list available blueprints from the library, formatted for the AI.
 */
export function listAvailableBlueprints() {
    const dir = getLibraryDir();
    const entries = listBlueprintLibrary(dir);
    if (entries.length === 0) {
        return 'No blueprints available in library. Add .litematic, .schem, .nbt, or .json files to: ' + dir;
    }
    const lines = entries.map(e => `  - ${e.name} (${e.format}, ${(e.size / 1024).toFixed(1)}KB)`);
    return `Available blueprints (${entries.length}):\n${lines.join('\n')}`;
}

/**
 * High-level: import a blueprint by name from the library and upload it.
 *
 * @param {string} name - Blueprint name (without extension)
 * @param {object} origin - {x, y, z} world position
 * @param {ModBlueprintClient} client - The mod blueprint client instance
 */
export async function importAndUpload(name, origin, client) {
    console.log(`[BP-DIAG] importAndUpload called: name="${name}", origin=`, JSON.stringify(origin), `, client connected=${client?.connected}`);
    const dir = getLibraryDir();
    const entries = listBlueprintLibrary(dir);

    // Find by name (case-insensitive)
    const match = entries.find(e => e.name.toLowerCase() === name.toLowerCase());
    if (!match) {
        const available = entries.map(e => e.name).join(', ');
        throw new Error(`Blueprint "${name}" not found. Available: ${available || 'none'}`);
    }

    const payload = await importSchematic(match.path, origin, match.name);
    console.log(`[BP-DIAG] importAndUpload: parsed "${match.name}" from ${match.path}, blocks=${payload.blocks.length}, origin=(${payload.ox},${payload.oy},${payload.oz}), sample[0..2]=`, JSON.stringify(payload.blocks.slice(0, 3)));
    if (payload.blocks.length === 0) {
        console.error(`[BP-DIAG] ⚠️ ZERO BLOCKS parsed from "${match.path}"! Schematic解析可能失败了。检查文件格式和prismarine-nbt。`);
    }
    const result = await client.uploadRaw(payload);

    if (!result || result.error) {
        throw new Error(`Upload failed: ${result?.error || client.lastError || 'unknown'}`);
    }

    return {
        id: payload.id,
        blockCount: payload.blocks.length,
        origin,
        ...result,
    };
}
