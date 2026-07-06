/**
 * 独立诊断脚本 - 测试 SynaBridge mod 的 HTTP 接口
 * 
 * 用法：
 *   node scripts/diagnose_mod.js
 *   node scripts/diagnose_mod.js --base http://127.0.0.1:8765
 *   node scripts/diagnose_mod.js --blueprint medieval-house-with-tree-converted
 * 
 * 不需要启动 AI，只需要 Minecraft 服务器 + mod 在运行。
 * 会依次测试：
 *   1. mod 是否在线 (GET /blueprint/list)
 *   2. 列出所有已注册蓝图
 *   3. 查询指定蓝图状态 (/status)
 *   4. 调用 /next 看返回什么
 *   5. 测试 /skip 路由是否存在
 */

function getArg(name, fallback = null) {
    // 支持 --name=value 和 --name value 两种格式
    const eqArg = process.argv.find(a => a.startsWith(`--${name}=`));
    if (eqArg) return eqArg.split('=').slice(1).join('=');
    const idx = process.argv.indexOf(`--${name}`);
    if (idx !== -1 && idx + 1 < process.argv.length) return process.argv[idx + 1];
    return fallback;
}

const BASE_URL = getArg('base', 'http://127.0.0.1:8765');
const TARGET_BP = getArg('blueprint', null);

const TIMEOUT = 3000;

async function request(path, { method = 'GET', body = null } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT);
    try {
        const init = {
            method,
            signal: controller.signal,
            headers: { 'Accept': 'application/json' },
        };
        if (body != null) {
            init.headers['Content-Type'] = 'application/json';
            init.body = typeof body === 'string' ? body : JSON.stringify(body);
        }
        const url = `${BASE_URL}${path}`;
        console.log(`  → ${method} ${url}${body ? ' body=' + JSON.stringify(body).slice(0, 100) : ''}`);
        const res = await fetch(url, init);
        const text = await res.text();
        let data = null;
        try { data = JSON.parse(text); } catch { data = { _raw: text }; }
        return { status: res.status, ok: res.ok, data };
    } catch (err) {
        return { status: 0, ok: false, data: null, error: err.message };
    } finally {
        clearTimeout(timer);
    }
}

function hr(title) {
    console.log(`\n${'═'.repeat(60)}`);
    console.log(`  ${title}`);
    console.log('═'.repeat(60));
}

async function main() {
    console.log('╔══════════════════════════════════════════════════════════╗');
    console.log('║   SynaBridge Mod 诊断工具                               ║');
    console.log('╚══════════════════════════════════════════════════════════╝');
    console.log(`  Base URL: ${BASE_URL}`);
    console.log(`  Target Blueprint: ${TARGET_BP || '(auto - will use first found)'}`);
    console.log(`  Time: ${new Date().toLocaleString()}`);

    // ─── Step 1: 测试连通性 ───
    hr('Step 1: 测试 mod HTTP 服务是否可达');
    const listRes = await request('/blueprint/list');
    if (!listRes.ok || listRes.error) {
        console.log(`  ❌ 连接失败！`);
        console.log(`     status=${listRes.status}, error=${listRes.error || 'HTTP error'}`);
        console.log(`     data=${JSON.stringify(listRes.data)?.slice(0, 200)}`);
        console.log(`\n  可能原因：`);
        console.log(`    - Minecraft 服务器没有运行`);
        console.log(`    - SynaBridge mod 没有加载`);
        console.log(`    - 端口不是 8765（检查 mod 配置）`);
        console.log(`    - 防火墙阻止了连接`);
        return;
    }
    console.log(`  ✅ mod HTTP 服务在线！`);
    console.log(`  响应: ${JSON.stringify(listRes.data)?.slice(0, 500)}`);

    // ─── Step 2: 列出已注册蓝图 ───
    hr('Step 2: 已注册蓝图列表');
    const blueprints = listRes.data?.blueprints || listRes.data?.ids || listRes.data || [];
    if (Array.isArray(blueprints) && blueprints.length > 0) {
        blueprints.forEach((bp, i) => {
            const name = typeof bp === 'string' ? bp : (bp.id || bp.name || JSON.stringify(bp));
            console.log(`  [${i}] ${name}`);
        });
    } else {
        console.log(`  ⚠️  没有已注册的蓝图。`);
        console.log(`     AI 需要先执行 !importBlueprint 上传蓝图到 mod。`);
        console.log(`     或者你可以手动测试上传（见 Step 6）。`);
    }

    // 选择要测试的蓝图
    let testId = TARGET_BP;
    if (!testId) {
        if (Array.isArray(blueprints) && blueprints.length > 0) {
            const first = blueprints[0];
            testId = typeof first === 'string' ? first : (first.id || first.name);
        }
    }

    if (!testId) {
        console.log(`\n  没有可测试的蓝图，跳过 Step 3-5。`);
        console.log(`  提示：用 --blueprint=xxx 指定蓝图 ID，或先让 AI 上传一个。`);
        await testSkipRoute();
        return;
    }

    // ─── Step 3: 查询蓝图状态 ───
    hr(`Step 3: 查询蓝图 "${testId}" 状态`);
    const statusRes = await request(`/blueprint/${testId}/status`);
    console.log(`  status=${statusRes.status}`);
    console.log(`  data=${JSON.stringify(statusRes.data)?.slice(0, 500)}`);
    const remaining = statusRes.data?.remaining ?? 0;
    const totalCells = statusRes.data?.total ?? 0;
    const doneCells = statusRes.data?.done ?? 0;
    if (remaining === 0 && totalCells > 0) {
        console.log(`  ⚠️  mod 认为这个蓝图已经完成了（remaining=0）！这就是 placed=0 的原因。`);
        console.log(`     可能原因：蓝图的 origin 坐标处的世界方块已经匹配了蓝图内容。`);
        console.log(`     解决方案：删除蓝图后重新上传，或者清空建筑区域。`);
    } else {
        console.log(`  ℹ️  进度: ${doneCells}/${totalCells} 已完成, ${remaining} 待放置`);
    }

    // ─── Step 4: 调用 /next ───
    hr(`Step 4: 调用 /next 获取下一个待放置方块`);
    // 用 (0,64,0) 作为假的玩家位置
    const nextRes = await request(`/blueprint/${testId}/next?fx=0&fy=64&fz=0`);
    console.log(`  status=${nextRes.status}`);
    console.log(`  data=${JSON.stringify(nextRes.data)?.slice(0, 500)}`);
    if (nextRes.data?.done) {
        console.log(`  ⚠️  /next 返回 done=true，没有待放置的方块。`);
        console.log(`     这意味着 mod 的 getNextMissing 认为所有方块都已放好。`);
        console.log(`     根因可能是：`);
        console.log(`       1. 蓝图 origin 坐标错误（世界中那个位置的方块恰好匹配）`);
        console.log(`       2. 蓝图内容全是 air/structure_void 等不需要放置的方块`);
        console.log(`       3. mod 的 getNextMissing 逻辑有 bug`);
    } else if (nextRes.data?.block || nextRes.data?.name) {
        const b = nextRes.data.block || nextRes.data;
        console.log(`  ✅ mod 返回了待放置方块: ${b.name} at (${b.x}, ${b.y}, ${b.z})`);
        console.log(`     这说明 mod 通信正常，问题可能在 placeBlock 环节。`);
    }

    // ─── Step 5: 测试 /skip 路由 ───
    await testSkipRoute(testId);

    // ─── 总结 ───
    hr('诊断完成');
    console.log(`  把以上输出贴给开发者，可以快速定位 placed=0 的根因。`);
}

async function testSkipRoute(testId) {
    hr('Step 5: 测试 /skip 路由是否存在');
    if (!testId) {
        // 用一个假 ID 测试路由是否存在
        testId = '__test_skip_route__';
    }
    const skipRes = await request(`/blueprint/${testId}/skip`, {
        method: 'POST',
        body: { x: 0, y: 64, z: 0, name: 'stone' }
    });
    console.log(`  status=${skipRes.status}`);
    console.log(`  data=${JSON.stringify(skipRes.data)?.slice(0, 300)}`);
    if (skipRes.data?.error === 'unknown_route' || skipRes.status === 404) {
        console.log(`  ❌ /skip 路由不存在！你的 mod JAR 太旧了。`);
        console.log(`     需要重新编译 syna_mod 并替换 mods 目录下的 JAR。`);
        console.log(`     源码中 BridgeHttpServer.java 已经有 handleSkip，但运行的 JAR 没有。`);
    } else if (skipRes.ok || skipRes.data?.ok) {
        console.log(`  ✅ /skip 路由存在且可用。`);
    } else {
        console.log(`  ⚠️  /skip 返回了错误，但路由存在（可能是蓝图 ID 不对）。`);
    }
}

main().catch(err => {
    console.error('诊断脚本出错:', err);
    process.exit(1);
});
