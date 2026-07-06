/**
 * modBuildNext 离线模拟测试
 * 用法: node scripts/test_modBuildNext.js
 * 不需要 MC 服务器，不需要 mod，纯逻辑验证
 */

// ═══ 黑名单 & 替换表（从 actions.js 复制） ═══
const BLOCK_BLACKLIST = new Set([
    'air', 'cave_air', 'void_air',
    'short_grass', 'grass', 'tall_grass', 'fern', 'large_fern',
    'dead_bush', 'seagrass', 'tall_seagrass', 'kelp', 'kelp_plant',
    'vine', 'glow_lichen', 'hanging_roots', 'moss_carpet',
    'small_dripleaf', 'big_dripleaf', 'big_dripleaf_stem',
    'sweet_berry_bush', 'cave_vines', 'cave_vines_plant',
    'spore_blossom', 'azalea', 'flowering_azalea',
    'poppy', 'dandelion', 'blue_orchid', 'allium', 'azure_bluet',
    'red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip',
    'oxeye_daisy', 'cornflower', 'lily_of_the_valley', 'torchflower',
    'pitcher_plant', 'wither_rose', 'sunflower', 'lilac',
    'rose_bush', 'peony', 'lily_pad',
    'structure_void', 'light', 'barrier', 'jigsaw', 'structure_block',
    'moving_piston', 'budding_amethyst',
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air',
    'minecraft:structure_void', 'minecraft:light',
    'minecraft:barrier', 'minecraft:jigsaw', 'minecraft:structure_block',
]);

const BLOCK_SUBSTITUTES = {
    'short_grass': 'fern', 'grass': 'fern', 'tall_grass': 'fern',
    'large_fern': 'fern', 'dead_bush': 'air', 'vine': 'oak_leaves',
    'glow_lichen': 'air', 'hanging_roots': 'air',
    'moss_carpet': 'green_carpet', 'sweet_berry_bush': 'oak_leaves',
    'cave_vines': 'air', 'cave_vines_plant': 'air',
    'spore_blossom': 'air', 'azalea': 'oak_leaves',
    'flowering_azalea': 'oak_leaves', 'fern': 'air',
    'budding_amethyst': 'amethyst_block', 'moving_piston': 'air',
    'poppy': 'red_wool', 'dandelion': 'yellow_wool',
    'blue_orchid': 'light_blue_wool', 'allium': 'magenta_wool',
    'lily_pad': 'green_carpet',
};

// ═══ 核心循环（从 actions.js 提取，去掉 await/bot 依赖） ═══
function simulateBuildLoop(cells, options = {}) {
    const {
        count = 64,
        placeBlockFn = () => true,  // 默认放置成功
        skipFn = () => ({ ok: true }), // 默认 skip 成功
        verbose = false,
    } = options;

    const n = Math.max(1, Math.min(64, count));
    const MAX_CONSECUTIVE_FAILS = 5;
    const MAX_SKIPS_PER_CALL = 128;

    const persistentSkipped = new Set();
    const failedCoords = new Set();
    const runtimeBlacklist = new Set();
    const localSkippedCoords = new Set();
    const typeFailCounts = {};

    let placed = 0, skipped = 0, consecutiveFails = 0;
    let cellIndex = 0;
    let lastReturnedCoord = null, sameCoordCount = 0;

    const log = verbose ? console.log : () => {};
    const results = { placed: 0, skipped: 0, errors: [], iterations: 0, setblockFallbacks: 0 };

    for (let i = 0; i < n + skipped; i++) {
        results.iterations++;
        if (skipped >= MAX_SKIPS_PER_CALL) {
            log(`  [STOP] MAX_SKIPS_PER_CALL reached (${MAX_SKIPS_PER_CALL})`);
            break;
        }
        if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
            log(`  [STOP] MAX_CONSECUTIVE_FAILS reached`);
            break;
        }

        // 模拟 mod 的 next() 返回
        if (cellIndex >= cells.length) {
            log(`  [DONE] all cells consumed`);
            break;
        }
        const cell = cells[cellIndex];
        // 如果 cell 有 repeat 标记，不推进 index（模拟 mod 重复返回同一坐标）
        if (!cell._repeated) cellIndex++;

        const { x, y, z, name } = cell;
        const thisCoord = `${x},${y},${z}`;

        // 持久跳过
        if (persistentSkipped.has(thisCoord)) {
            skipped++;
            continue;
        }

        // 重复坐标检测
        if (thisCoord === lastReturnedCoord) {
            sameCoordCount++;
            if (sameCoordCount >= 3) {
                log(`  [PERSISTENT_SKIP] same coord ${thisCoord} x${sameCoordCount}`);
                persistentSkipped.add(thisCoord);
                skipped++;
                sameCoordCount = 0;
                lastReturnedCoord = null;
                continue;
            }
        } else {
            sameCoordCount = 1;
            lastReturnedCoord = thisCoord;
        }

        // 本地已跳过
        if (localSkippedCoords.has(thisCoord)) {
            persistentSkipped.add(thisCoord);
            skipped++;
            continue;
        }

        // 黑名单检查
        const normalizedName = (name || '').replace(/^minecraft:/, '');
        if (BLOCK_BLACKLIST.has(name) || BLOCK_BLACKLIST.has(normalizedName) || runtimeBlacklist.has(normalizedName)) {
            const substitute = BLOCK_SUBSTITUTES[normalizedName] || BLOCK_SUBSTITUTES[name];
            if (substitute && substitute !== 'air') {
                if (BLOCK_BLACKLIST.has(substitute)) {
                    log(`  [SKIP] substitute "${substitute}" also blacklisted for "${normalizedName}" at (${x},${y},${z})`);
                    const skipRes = skipFn(x, y, z, name);
                    if (!skipRes || skipRes.ok === false) localSkippedCoords.add(thisCoord);
                    skipped++;
                    continue;
                }
                // 放置替代品
                const subOk = placeBlockFn(substitute, x, y, z);
                if (!subOk) {
                    log(`  [SETBLOCK_FALLBACK] substitute "${substitute}" at (${x},${y},${z})`);
                    results.setblockFallbacks++;
                }
                skipFn(x, y, z, name); // 通知 mod
                placed++;
                consecutiveFails = 0;
                continue;
            }
            // 纯跳过
            const skipRes = skipFn(x, y, z, name);
            if (!skipRes || skipRes.ok === false) {
                localSkippedCoords.add(thisCoord);
            }
            log(`  [BLACKLIST_SKIP] "${name}" at (${x},${y},${z})`);
            skipped++;
            continue;
        }

        // 坐标有效性
        if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
            results.errors.push(`Invalid coord: (${x},${y},${z}) for "${name}"`);
            break;
        }

        // 已失败坐标 -> setblock fallback
        const coordKey = `${x},${y},${z}`;
        if (failedCoords.has(coordKey)) {
            log(`  [SETBLOCK_RETRY] ${name} at ${coordKey}`);
            results.setblockFallbacks++;
            placed++;
            consecutiveFails = 0;
            continue;
        }

        // 正常放置
        const ok = placeBlockFn(name, x, y, z);
        if (!ok) {
            log(`  [PLACE_FAIL] ${name} at (${x},${y},${z}) -> /setblock fallback`);
            failedCoords.add(coordKey);
            results.setblockFallbacks++;
            const failKey = `__typefail_${normalizedName}`;
            typeFailCounts[failKey] = (typeFailCounts[failKey] || 0) + 1;
            if (typeFailCounts[failKey] >= 3) {
                runtimeBlacklist.add(normalizedName);
                log(`  [RUNTIME_BLACKLIST] "${normalizedName}"`);
            }
            placed++;
            consecutiveFails = 0;
            continue;
        }
        placed++;
        consecutiveFails = 0;
    }

    results.placed = placed;
    results.skipped = skipped;
    results.runtimeBlacklist = [...runtimeBlacklist];
    results.persistentSkipped = persistentSkipped.size;
    return results;
}

// ═══ 测试场景 ═══
function runTests() {
    let passed = 0, failed = 0;

    function assert(cond, msg) {
        if (cond) { passed++; console.log(`  ✓ ${msg}`); }
        else { failed++; console.log(`  ✗ FAIL: ${msg}`); }
    }

    // --- 测试 1: 正常方块全部放置成功 ---
    console.log('\n[TEST 1] 正常方块 - 全部放置成功');
    const normalCells = [];
    for (let i = 0; i < 64; i++) {
        normalCells.push({ x: i, y: 60, z: 0, name: 'oak_planks' });
    }
    const r1 = simulateBuildLoop(normalCells, { count: 64 });
    assert(r1.placed === 64, `placed=${r1.placed} should be 64`);
    assert(r1.skipped === 0, `skipped=${r1.skipped} should be 0`);
    assert(r1.iterations === 64, `iterations=${r1.iterations} should be 64`);

    // --- 测试 2: 全是黑名单方块（air/short_grass） ---
    console.log('\n[TEST 2] 全是黑名单方块 - 应全部跳过');
    const blackCells = [
        { x: 0, y: 60, z: 0, name: 'air' },
        { x: 1, y: 60, z: 0, name: 'short_grass' },
        { x: 2, y: 60, z: 0, name: 'cave_air' },
        { x: 3, y: 60, z: 0, name: 'fern' },
        { x: 4, y: 60, z: 0, name: 'tall_grass' },
        { x: 5, y: 60, z: 0, name: 'poppy' },
    ];
    const r2 = simulateBuildLoop(blackCells, { count: 64 });
    // poppy -> red_wool (有效替代品，会被放置), 其余5个替代品是air或fern(也在黑名单)所以跳过
    assert(r2.placed === 1, `placed=${r2.placed} should be 1 (poppy->red_wool is placed)`);
    assert(r2.skipped === 5, `skipped=${r2.skipped} should be 5`);

    // --- 测试 3: short_grass 替代品 fern 也在黑名单 -> 不死循环 ---
    console.log('\n[TEST 3] short_grass -> fern (fern也在黑名单) 不死循环');
    const grassCells = [];
    for (let i = 0; i < 200; i++) {
        grassCells.push({ x: i, y: 60, z: 0, name: 'short_grass' });
    }
    const r3 = simulateBuildLoop(grassCells, { count: 64 });
    assert(r3.iterations <= 192, `iterations=${r3.iterations} should be <= 192 (bounded by MAX_SKIPS)`);
    assert(r3.placed === 0, `placed=${r3.placed} should be 0`);

    // --- 测试 4: mod 重复返回同一坐标 -> 触发 persistent skip ---
    console.log('\n[TEST 4] mod重复返回同一坐标 -> persistent skip');
    const repeatCells = [
        { x: 5, y: 60, z: 5, name: 'stone', _repeated: true },
        { x: 5, y: 60, z: 5, name: 'stone', _repeated: true },
        { x: 5, y: 60, z: 5, name: 'stone', _repeated: true },
        { x: 5, y: 60, z: 5, name: 'stone' }, // 第4次，已被 persistent skip
        { x: 6, y: 60, z: 5, name: 'stone' },
    ];
    const r4 = simulateBuildLoop(repeatCells, {
        count: 10,
        placeBlockFn: (name, x, y, z) => false, // 放置总是失败
    });
    assert(r4.persistentSkipped === 1, `persistentSkipped=${r4.persistentSkipped} should be 1`);

    // --- 测试 5: NaN 坐标 -> 立即中断 ---
    console.log('\n[TEST 5] NaN坐标 -> 立即中断');
    const nanCells = [
        { x: 0, y: 60, z: 0, name: 'stone' },
        { x: NaN, y: undefined, z: 0, name: 'stone' },
        { x: 2, y: 60, z: 0, name: 'stone' },
    ];
    const r5 = simulateBuildLoop(nanCells, { count: 10 });
    assert(r5.placed === 1, `placed=${r5.placed} should be 1 (stopped at NaN)`);
    assert(r5.errors.length === 1, `errors=${r5.errors.length} should be 1`);

    // --- 测试 6: placeBlock 连续失败 -> runtime blacklist ---
    console.log('\n[TEST 6] 同类型连续失败3次 -> runtime blacklist');
    const failCells = [];
    for (let i = 0; i < 10; i++) {
        failCells.push({ x: i, y: 60, z: 0, name: 'weird_block' });
    }
    const r6 = simulateBuildLoop(failCells, {
        count: 10,
        placeBlockFn: (name) => name !== 'weird_block' ? true : false,
    });
    assert(r6.runtimeBlacklist.includes('weird_block'), `weird_block should be in runtimeBlacklist`);

    // --- 测试 7: 混合场景（模拟真实蓝图） ---
    console.log('\n[TEST 7] 混合场景 - 正常+黑名单+失败');
    const mixCells = [
        { x: 0, y: 60, z: 0, name: 'oak_planks' },
        { x: 1, y: 60, z: 0, name: 'short_grass' },
        { x: 2, y: 60, z: 0, name: 'oak_log' },
        { x: 3, y: 60, z: 0, name: 'air' },
        { x: 4, y: 60, z: 0, name: 'glass_pane' },
        { x: 5, y: 60, z: 0, name: 'vine' },  // substitute -> oak_leaves
        { x: 6, y: 60, z: 0, name: 'stone_bricks' },
    ];
    const r7 = simulateBuildLoop(mixCells, { count: 64 });
    assert(r7.placed >= 4, `placed=${r7.placed} should be >= 4`);
    assert(r7.skipped >= 2, `skipped=${r7.skipped} should be >= 2`);

    // --- 测试 8: MAX_SKIPS_PER_CALL 保护 ---
    console.log('\n[TEST 8] MAX_SKIPS_PER_CALL=128 保护不死循环');
    const megaBlackCells = [];
    for (let i = 0; i < 500; i++) {
        megaBlackCells.push({ x: i, y: 60, z: 0, name: 'air' });
    }
    const r8 = simulateBuildLoop(megaBlackCells, { count: 64 });
    assert(r8.skipped === 128, `skipped=${r8.skipped} should be capped at 128`);
    assert(r8.iterations <= 192, `iterations=${r8.iterations} should be bounded`);

    // ═══ 总结 ═══
    console.log(`\n${'═'.repeat(50)}`);
    console.log(`结果: ${passed} passed, ${failed} failed`);
    if (failed > 0) process.exit(1);
}

runTests();
