/**
 * 蓝图连接诊断脚本
 * 
 * 用法: node scripts/test_blueprint_connection.js [blueprint_id]
 * 
 * 这个脚本会：
 * 1. 测试 mod HTTP 服务是否可达
 * 2. 列出所有已注册蓝图
 * 3. 查询指定蓝图的状态
 * 4. 尝试获取下一个待放置方块
 * 
 * 不需要启动 bot，直接测试 mod 端的 HTTP API。
 */

const BASE_URL = 'http://127.0.0.1:8765';
const TIMEOUT = 3000;

async function request(path, { method = 'GET', body = null } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT);
    const url = `${BASE_URL}${path}`;
    console.log(`  >>> ${method} ${url}`);
    try {
        const init = { method, signal: controller.signal, headers: { 'Accept': 'application/json' } };
        if (body) {
            init.headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(body);
        }
        const res = await fetch(url, init);
        const text = await res.text();
        let data;
        try { data = JSON.parse(text); } catch { data = { raw: text }; }
        console.log(`  <<< status=${res.status} data=${JSON.stringify(data).slice(0, 500)}`);
        return { status: res.status, data };
    } catch (err) {
        console.log(`  !!! ERROR: ${err.message}`);
        return { status: 0, data: null, error: err.message };
    } finally {
        clearTimeout(timer);
    }
}

async function main() {
    const targetId = process.argv[2] || null;
    
    console.log('='.repeat(60));
    console.log('蓝图系统连接诊断');
    console.log('='.repeat(60));
    console.log(`目标: ${BASE_URL}`);
    console.log(`时间: ${new Date().toLocaleString()}`);
    console.log('');

    // Step 1: 测试连通性
    console.log('--- [1] 测试 mod HTTP 服务连通性 ---');
    const listResult = await request('/blueprint/list');
    if (listResult.status === 0) {
        console.log('\n❌ 无法连接到 mod HTTP 服务！');
        console.log('   可能原因:');
        console.log('   - Minecraft 没有运行');
        console.log('   - SynaBridge mod 没有加载');
        console.log('   - 端口 8765 被占用或被防火墙阻止');
        console.log('   - mod 的 HTTP 服务没有启动');
        return;
    }
    console.log('✓ mod HTTP 服务可达\n');

    // Step 2: 列出蓝图
    console.log('--- [2] 已注册蓝图列表 ---');
    const blueprints = listResult.data?.blueprints || listResult.data || [];
    if (Array.isArray(blueprints) && blueprints.length > 0) {
        for (const bp of blueprints) {
            if (typeof bp === 'string') {
                console.log(`  - ${bp}`);
            } else {
                console.log(`  - id="${bp.id}" total=${bp.total ?? '?'} placed=${bp.placed ?? '?'} origin=(${bp.ox ?? '?'},${bp.oy ?? '?'},${bp.oz ?? '?'})`);
            }
        }
    } else {
        console.log('  (空 - 没有已注册的蓝图)');
        console.log('  提示: 需要先让 AI 执行 !modUploadBlueprint 上传蓝图');
    }
    console.log('');

    // Step 3: 查询指定蓝图状态
    const checkId = targetId || (Array.isArray(blueprints) && blueprints.length > 0 
        ? (typeof blueprints[0] === 'string' ? blueprints[0] : blueprints[0]?.id) 
        : null);
    
    if (!checkId) {
        console.log('--- [3] 跳过 (没有可查询的蓝图) ---\n');
        return;
    }

    console.log(`--- [3] 蓝图 "${checkId}" 状态 ---`);
    const statusResult = await request(`/blueprint/${encodeURIComponent(checkId)}/status`);
    if (statusResult.data) {
        const s = statusResult.data;
        console.log(`  id: ${s.id}`);
        console.log(`  origin: (${s.ox}, ${s.oy}, ${s.oz})`);
        console.log(`  total cells: ${s.total}`);
        console.log(`  placed: ${s.placed}`);
        console.log(`  remaining: ${s.remaining ?? (s.total - s.placed)}`);
        console.log(`  mode: ${s.mode}`);
        console.log(`  done: ${s.done}`);
        
        if (s.total === 0) {
            console.log('\n  ⚠️  total=0! 蓝图数据为空！');
            console.log('     这说明上传时 blocks 数组是空的。');
            console.log('     检查 schematic_importer.js 的转换逻辑。');
        }
        if (s.ox === undefined || s.oy === undefined || s.oz === undefined) {
            console.log('\n  ⚠️  origin 坐标缺失！');
        }
    }
    console.log('');

    // Step 4: 获取下一个待放置方块
    console.log(`--- [4] 获取下一个待放置方块 (next) ---`);
    // 用 (0,64,0) 作为假的 bot 位置
    const nextResult = await request(`/blueprint/${encodeURIComponent(checkId)}/next?fx=0&fy=64&fz=0`);
    if (nextResult.data) {
        const n = nextResult.data;
        if (n.done) {
            console.log('  蓝图已完成 (done=true)');
        } else if (n.block || n.x !== undefined) {
            const block = n.block || { x: n.x, y: n.y, z: n.z, name: n.name };
            console.log(`  下一个方块: ${block.name} at (${block.x}, ${block.y}, ${block.z})`);
            console.log(`  进度: ${n.placed_so_far ?? '?'}/${n.total ?? '?'}`);
            
            if (!Number.isFinite(block.x) || !Number.isFinite(block.y) || !Number.isFinite(block.z)) {
                console.log('\n  ⚠️  坐标无效 (NaN/undefined)！');
                console.log('     这说明 mod 端的 origin 解析有问题。');
            }
        } else if (n.ok === false) {
            console.log(`  错误: ${n.error}`);
        } else {
            console.log(`  未知响应格式: ${JSON.stringify(n).slice(0, 300)}`);
        }
    }
    console.log('');

    // Step 5: 测试 /skip 端点
    console.log(`--- [5] 测试 /skip 端点可用性 ---`);
    // 发一个不存在的坐标，只是测试路由是否存在
    const skipResult = await request(`/blueprint/${encodeURIComponent(checkId)}/skip`, {
        method: 'POST',
        body: { x: -99999, y: -99999, z: -99999, name: 'test_skip' }
    });
    if (skipResult.status === 0) {
        console.log('  ❌ /skip 端点不可达');
    } else if (skipResult.status === 404) {
        console.log('  ❌ /skip 路由不存在！mod 版本可能太旧。');
        console.log('     需要重新编译部署包含 /skip handler 的 mod。');
    } else {
        console.log(`  ✓ /skip 端点响应正常 (status=${skipResult.status})`);
    }
    console.log('');

    console.log('='.repeat(60));
    console.log('诊断完成');
    console.log('='.repeat(60));
}

main().catch(e => {
    console.error('诊断脚本异常:', e);
    process.exit(1);
});
