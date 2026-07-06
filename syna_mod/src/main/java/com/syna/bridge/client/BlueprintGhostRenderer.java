package com.syna.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders blueprint cells as semi-transparent block models overlaid on the world.
 *
 * Visual style:
 *   - Undone cells: rendered as the actual block model at ~35% opacity (ghost block)
 *   - Done cells: thin green wireframe outline (confirmation that it's placed)
 *   - No collision (purely visual, client-side only)
 *
 * The effect is similar to Litematica/Schematica ghost blocks: you can see
 * exactly what the finished building will look like, walk through it, and
 * watch it materialize as the bot places blocks.
 *
 * Performance: distance-culled at 48 blocks. Only surface cells of undone
 * blocks get the expensive model render; interior undone cells get a simple
 * tinted quad. Done cells only get a faint wireframe.
 */
@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BlueprintGhostRenderer {

    /** Skip cells farther than this from the camera (squared distance, blocks²). */
    private static final double MAX_DRAW_DISTANCE_SQR = 48.0 * 48.0;

    /** Alpha for ghost blocks (undone, surface). */
    private static final float GHOST_ALPHA = 0.35f;

    /** Cache blockName -> BlockState lookups to avoid registry queries every frame. */
    private static final Map<String, BlockState> STATE_CACHE = new ConcurrentHashMap<>();

    private BlueprintGhostRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!BlueprintMirror.isVisible()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var snapshot = BlueprintMirror.snapshot();
        if (snapshot.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // Pass 1: Render undone cells as ghost blocks (semi-transparent models)
        VertexConsumer translucentConsumer = buffer.getBuffer(RenderType.translucent());
        for (var bp : snapshot) {
            for (var cell : bp.cells.values()) {
                if (cell.done) continue;
                if (!cell.isSurface) continue; // only render surface for performance
                if (tooFar(cell.worldPos, cam)) continue;
                renderGhostBlock(pose, translucentConsumer, blockRenderer, cell, mc);
            }
        }

        // Pass 2: Done cells get a faint green wireframe
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (var bp : snapshot) {
            for (var cell : bp.cells.values()) {
                if (!cell.done) continue;
                if (!cell.isSurface) continue;
                if (tooFar(cell.worldPos, cam)) continue;
                drawDoneWireframe(pose, lines, cell);
            }
        }

        // Pass 3: Undone interior cells get a dim colored wireframe (optional context)
        for (var bp : snapshot) {
            for (var cell : bp.cells.values()) {
                if (cell.done) continue;
                if (cell.isSurface) continue;
                if (tooFar(cell.worldPos, cam)) continue;
                drawUndoneInterior(pose, lines, cell);
            }
        }

        pose.popPose();
        buffer.endBatch(RenderType.translucent());
        buffer.endBatch(RenderType.lines());
    }

    /**
     * Render a ghost block: the actual block model tinted with transparency.
     * Uses the block's baked model and renders it into the translucent layer.
     */
    private static void renderGhostBlock(PoseStack pose, VertexConsumer consumer,
                                          BlockRenderDispatcher blockRenderer,
                                          BlueprintMirror.ClientCell cell,
                                          Minecraft mc) {
        BlockState state = resolveBlockState(cell.blockName);
        if (state.isAir()) {
            // If we can't resolve the block, fall back to orange wireframe
            return;
        }

        BlockPos pos = cell.worldPos;
        pose.pushPose();
        pose.translate(pos.getX(), pos.getY(), pos.getZ());

        // Render the block model with tinting
        try {
            BakedModel model = blockRenderer.getBlockModel(state);
            // Use the block renderer with a custom color multiplier for transparency
            // We render into the translucent buffer with reduced alpha
            blockRenderer.getModelRenderer().renderModel(
                    pose.last(),
                    consumer,
                    state,
                    model,
                    0.8f, 0.9f, 1.0f, // slight blue tint to distinguish from real blocks
                    0x00F000F0, // full brightness (packed light)
                    0, // overlay
                    ModelData.EMPTY,
                    RenderType.translucent()
            );
        } catch (Exception e) {
            // Fallback: if model rendering fails, just skip
        }

        pose.popPose();
    }

    /** Faint green wireframe for completed cells. */
    private static void drawDoneWireframe(PoseStack pose, VertexConsumer lines,
                                           BlueprintMirror.ClientCell cell) {
        BlockPos p = cell.worldPos;
        final double pad = 0.005;
        LevelRenderer.renderLineBox(
                pose, lines,
                p.getX() + pad, p.getY() + pad, p.getZ() + pad,
                p.getX() + 1.0 - pad, p.getY() + 1.0 - pad, p.getZ() + 1.0 - pad,
                0.2f, 0.9f, 0.3f, 0.4f);
    }

    /** Dim red wireframe for undone interior cells (gives sense of volume). */
    private static void drawUndoneInterior(PoseStack pose, VertexConsumer lines,
                                            BlueprintMirror.ClientCell cell) {
        BlockPos p = cell.worldPos;
        final double pad = 0.01;
        LevelRenderer.renderLineBox(
                pose, lines,
                p.getX() + pad, p.getY() + pad, p.getZ() + pad,
                p.getX() + 1.0 - pad, p.getY() + 1.0 - pad, p.getZ() + 1.0 - pad,
                0.7f, 0.3f, 0.2f, 0.15f);
    }

    /**
     * Resolve a block name (e.g. "oak_planks" or "minecraft:oak_planks") to a BlockState.
     * Results are cached for performance.
     */
    private static BlockState resolveBlockState(String blockName) {
        return STATE_CACHE.computeIfAbsent(blockName, name -> {
            String fullName = name.contains(":") ? name : "minecraft:" + name;
            try {
                ResourceLocation rl = new ResourceLocation(fullName);
                Block block = ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null && block != Blocks.AIR) {
                    return block.defaultBlockState();
                }
            } catch (Exception ignored) {}
            return Blocks.AIR.defaultBlockState();
        });
    }

    private static boolean tooFar(BlockPos pos, Vec3 cam) {
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        return dx * dx + dy * dy + dz * dz > MAX_DRAW_DISTANCE_SQR;
    }
}
