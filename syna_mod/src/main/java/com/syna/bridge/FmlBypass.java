package com.syna.bridge;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;

import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * FmlBypass — 让 vanilla 协议客户端（mineflayer/syna bot）进入 Forge 1.20.1 服。
 *
 * ===== 核心策略（v3 - 2026-05-20） =====
 *
 * 1. relaxChannelValidation()
 *    把每个 NetworkInstance 的 clientAcceptedVersions / serverAcceptedVersions
 *    Predicate 替换成永远 true。
 *
 * 2. disableLoginPayloads()
 *    把每个 NetworkInstance 的 loginPacketHandler（BiConsumer 或 Supplier<List>）
 *    替换成空实现，使 NetworkRegistry.gatherLoginPayloads() 不会为任何 channel
 *    生成 login_plugin_request。这样 vanilla 客户端根本不会收到 FML query。
 *
 * 3. hookServerConnections(server)
 *    在 Netty pipeline 中安装 guard handler 作为最后防线：
 *    如果仍有 login_plugin_response 到达且 payload 为空，直接吞掉。
 *
 * ===== 标注（防失忆） =====
 * [BUG-FIX-NOTE] 之前只做了 relaxChannelValidation，Forge 仍然发 login queries，
 * 客户端回复空 payload 导致 IndexOutOfBoundsException。
 * 现在新增 disableLoginPayloads() 从源头阻止 query 发送。
 */
public final class FmlBypass {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "[SynaFmlBypass]";

    private static volatile boolean applied = false;

    private FmlBypass() {}

    /** 主入口：放行所有 FML 校验 + 禁用 login payloads。线程安全，可重复调用。 */
    public static synchronized void applyAll() {
        if (applied) {
            LOG.info("{} already applied, skip.", TAG);
            return;
        }
        relaxChannelValidation();
        disableLoginPayloads();
        applied = true;
        LOG.info("{} applyAll complete.", TAG);
    }

    /** 状态查询 */
    public static boolean isApplied() {
        return applied;
    }

    /**
     * 在 ServerConnectionListener 的 channel pipeline 上安装 guard。
     * 应在 ServerStartingEvent 中调用。
     */
    public static void hookServerConnections(Object server) {
        try {
            Object connectionListener = findConnectionListener(server);
            if (connectionListener == null) {
                LOG.warn("{} Could not find ServerConnectionListener on server", TAG);
                return;
            }

            // Find the List<ChannelFuture> channels field
            for (Field f : connectionListener.getClass().getDeclaredFields()) {
                if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object list = f.get(connectionListener);
                if (list instanceof java.util.List<?> theList && !theList.isEmpty()) {
                    Object first = theList.get(0);
                    if (first instanceof io.netty.channel.ChannelFuture cf) {
                        cf.channel().pipeline().addFirst("syna_connection_init",
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof Channel childChannel) {
                                        installGuardOnChild(childChannel);
                                    }
                                    super.channelRead(ctx, msg);
                                }
                            });
                        LOG.info("{} Hooked server channel pipeline for login query guard", TAG);
                        return;
                    }
                }
            }
            LOG.warn("{} Could not find channels list in ServerConnectionListener", TAG);
        } catch (Throwable t) {
            LOG.error("{} hookServerConnections failed: {}", TAG, t.toString());
        }
    }

    // =====================================================================
    // Plan-A: 放行 NetworkRegistry channel 版本校验
    // =====================================================================

    private static void relaxChannelValidation() {
        int patched = 0;
        try {
            Map<?, ?> instances = getNetworkInstances();
            if (instances == null) {
                LOG.warn("{} NetworkRegistry.instances not found.", TAG);
                return;
            }
            for (Object inst : instances.values()) {
                if (inst == null) continue;
                patched += relaxAllPredicates(inst);
            }
            LOG.info("{} relaxed {} version predicates (total {} channel entries).",
                    TAG, patched, instances.size());
        } catch (Throwable t) {
            LOG.error("{} relaxChannelValidation failed: {}", TAG, t.toString());
        }
    }

    // =====================================================================
    // Plan-B: 禁用 login payloads（核心新增）
    // =====================================================================

    /**
     * 遍历 NetworkRegistry 中所有 NetworkInstance，把它们的 loginPacketHandler
     * 相关字段替换为空实现。
     *
     * Forge 1.20.1 中 NetworkInstance 有以下相关字段：
     * - loginPacketHandler: BiConsumer<..., Consumer<...>> 或类似签名
     * - 可能还有 Supplier<List<Pair<...>>> 类型的字段
     *
     * 我们把所有 BiConsumer / Supplier / Consumer / Function 类型的字段
     * 中名字包含 "login" 的替换为空实现。
     * 如果找不到名字匹配的，就替换所有 BiConsumer 和 Supplier 字段。
     */
    private static void disableLoginPayloads() {
        int patched = 0;
        try {
            Map<?, ?> instances = getNetworkInstances();
            if (instances == null) {
                LOG.warn("{} Cannot disable login payloads: instances map not found", TAG);
                return;
            }
            for (Object inst : instances.values()) {
                if (inst == null) continue;
                patched += nullifyLoginHandlers(inst);
            }
            LOG.info("{} disabled login payload handlers on {} fields.", TAG, patched);
        } catch (Throwable t) {
            LOG.error("{} disableLoginPayloads failed: {}", TAG, t.toString());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int nullifyLoginHandlers(Object inst) {
        int count = 0;
        Class<?> c = inst.getClass();
        // First pass: look for fields with "login" in name
        boolean foundLoginField = false;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String name = f.getName().toLowerCase();
            if (!name.contains("login")) continue;
            foundLoginField = true;
            if (tryNullifyFunctionalField(inst, f)) count++;
        }
        // Second pass: if no login-named field found, nullify all BiConsumer/Supplier fields
        if (!foundLoginField) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (BiConsumer.class.isAssignableFrom(ft)
                    || Supplier.class.isAssignableFrom(ft)) {
                    if (tryNullifyFunctionalField(inst, f)) count++;
                }
            }
        }
        return count;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean tryNullifyFunctionalField(Object inst, Field f) {
        try {
            f.setAccessible(true);
            Class<?> ft = f.getType();
            if (BiConsumer.class.isAssignableFrom(ft)) {
                f.set(inst, (BiConsumer) (a, b) -> { /* no-op */ });
                LOG.debug("{} nullified BiConsumer field: {}", TAG, f.getName());
                return true;
            } else if (Supplier.class.isAssignableFrom(ft)) {
                f.set(inst, (Supplier) () -> Collections.emptyList());
                LOG.debug("{} nullified Supplier field: {}", TAG, f.getName());
                return true;
            } else if (java.util.function.Consumer.class.isAssignableFrom(ft)) {
                f.set(inst, (java.util.function.Consumer) (a) -> { /* no-op */ });
                LOG.debug("{} nullified Consumer field: {}", TAG, f.getName());
                return true;
            } else if (java.util.function.Function.class.isAssignableFrom(ft)) {
                f.set(inst, (java.util.function.Function) (a) -> null);
                LOG.debug("{} nullified Function field: {}", TAG, f.getName());
                return true;
            } else if (List.class.isAssignableFrom(ft)) {
                f.set(inst, Collections.emptyList());
                LOG.debug("{} cleared List field: {}", TAG, f.getName());
                return true;
            }
        } catch (Throwable t) {
            LOG.warn("{} could not nullify field '{}': {}", TAG, f.getName(), t.toString());
        }
        return false;
    }

    // =====================================================================
    // Netty pipeline guard (last resort)
    // =====================================================================

    private static void installGuardOnChild(Channel childChannel) {
        childChannel.pipeline().addFirst("syna_child_init",
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext childCtx) throws Exception {
                    childCtx.pipeline().addLast("syna_login_guard",
                        new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext gCtx, Object pkt) throws Exception {
                                String className = pkt.getClass().getSimpleName();
                                if (className.contains("CustomQuery") && className.contains("erbound")) {
                                    Object payload = extractPayload(pkt);
                                    if (payload == null) {
                                        LOG.info("{} Guard: dropping empty login query response ({})", TAG, className);
                                        return; // swallow
                                    }
                                }
                                super.channelRead(gCtx, pkt);
                            }
                        });
                    super.channelActive(childCtx);
                }
            });
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private static Object findConnectionListener(Object server) throws Exception {
        // server is MinecraftServer
        for (Class<?> clz = server.getClass(); clz != null && clz != Object.class; clz = clz.getSuperclass()) {
            for (Field f : clz.getDeclaredFields()) {
                String typeName = f.getType().getSimpleName();
                if (typeName.contains("ServerConnectionListener") || typeName.contains("ServerConnection")) {
                    f.setAccessible(true);
                    Object val = f.get(server);
                    if (val != null) return val;
                }
            }
        }
        return null;
    }

    /** 反射获取 Connection 内部的 Netty Channel */
    private static Channel getChannel(Connection connection) {
        for (Field f : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    return (Channel) f.get(connection);
                } catch (Throwable t) {
                    LOG.debug("{} getChannel via field '{}' failed: {}", TAG, f.getName(), t.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 反射提取 packet 中的 payload/data 字段。
     * 如果字段值为 null 或者是空的 Optional，返回 null。
     */
    private static Object extractPayload(Object packet) {
        for (Field f : packet.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(packet);
                if (val == null) return null;
                if (val instanceof java.util.Optional<?> opt) {
                    return opt.orElse(null);
                }
                if (val instanceof byte[] arr && arr.length == 0) return null;
                if (val instanceof io.netty.buffer.ByteBuf buf && buf.readableBytes() == 0) return null;
                return val;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** 获取 NetworkRegistry 的 instances map */
    private static Map<?, ?> getNetworkInstances() {
        try {
            Class<?> nrClass = Class.forName("net.minecraftforge.network.NetworkRegistry");
            // Try known field names
            for (String name : new String[] { "instances", "INSTANCES" }) {
                try {
                    Field f = nrClass.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Map<?, ?> m) return m;
                } catch (NoSuchFieldException ignore) {}
            }
            // Fallback: scan all static Map fields
            for (Field f : nrClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                        LOG.info("{} fallback to field '{}' (size={}).", TAG, f.getName(), m.size());
                        return m;
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            LOG.error("{} getNetworkInstances failed: {}", TAG, t.toString());
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int relaxAllPredicates(Object inst) {
        int count = 0;
        Class<?> c = inst.getClass();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (!Predicate.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                f.set(inst, (Predicate) s -> true);
                count++;
            } catch (Throwable t) {
                LOG.warn("{} could not relax field '{}': {}", TAG, f.getName(), t.toString());
            }
        }
        return count;
    }
}
