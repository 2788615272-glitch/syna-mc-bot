package com.syna.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.syna.bridge.AliceEntity;
import com.syna.bridge.SynaBridgeMod;
import com.syna.bridge.SynaController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SynaDebugRenderer {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("syna.debugRenderer", "false"));

    private SynaDebugRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ENABLED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        SynaController controller = SynaController.get();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        AliceEntity syna = findClientSyna(minecraft);
        if (syna == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        renderEntityMarker(poseStack, lines, syna);
        if (controller.isMobilityTaskActive()) {
            renderBox(poseStack, lines, controller.getMobilityTarget(), 0.1F, 1.0F, 0.1F, 1.0F);
            renderBox(poseStack, lines, controller.getMobilityDigTarget(), 1.0F, 0.1F, 0.1F, 1.0F);
            renderBox(poseStack, lines, controller.getMobilityDigHeadTarget(), 1.0F, 0.25F, 0.25F, 1.0F);
            renderBox(poseStack, lines, controller.getMobilitySupportTarget(), 0.15F, 0.45F, 1.0F, 1.0F);
            renderGoalLine(poseStack, lines, syna, controller.getMobilityGoal());
        }

        poseStack.popPose();
        buffer.endBatch(RenderType.lines());

        String detail = controller.isMobilityTaskActive()
                ? controller.getMobilityDetail()
                : "SynaDebug ON | mobility=idle";
        renderFloatingDetail(event, minecraft, buffer, syna, detail);
    }

    private static AliceEntity findClientSyna(Minecraft minecraft) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AliceEntity alice && alice.isAlive()) {
                return alice;
            }
        }
        return null;
    }

    private static void renderEntityMarker(PoseStack poseStack, VertexConsumer lines, AliceEntity syna) {
        LevelRenderer.renderLineBox(
                poseStack,
                lines,
                syna.getBoundingBox().inflate(0.08D),
                1.0F, 0.2F, 1.0F, 1.0F
        );
    }

    private static void renderBox(PoseStack poseStack, VertexConsumer lines, BlockPos pos, float red, float green, float blue, float alpha) {
        if (pos == null) {
            return;
        }
        LevelRenderer.renderLineBox(
                poseStack,
                lines,
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
                red, green, blue, alpha
        );
    }

    private static void renderGoalLine(PoseStack poseStack, VertexConsumer lines, AliceEntity syna, BlockPos goal) {
        if (goal == null) {
            return;
        }
        Vec3 from = syna.position().add(0.0D, syna.getBbHeight() * 0.75D, 0.0D);
        Vec3 to = Vec3.atCenterOf(goal);
        Matrix4f matrix = poseStack.last().pose();
        lines.vertex(matrix, (float) from.x, (float) from.y, (float) from.z).color(0.2F, 1.0F, 1.0F, 1.0F).normal(0.0F, 1.0F, 0.0F).endVertex();
        lines.vertex(matrix, (float) to.x, (float) to.y, (float) to.z).color(0.2F, 1.0F, 1.0F, 1.0F).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    private static void renderFloatingDetail(RenderLevelStageEvent event,
                                             Minecraft minecraft,
                                             MultiBufferSource.BufferSource buffer,
                                             AliceEntity syna,
                                             String detail) {
        if (detail == null || detail.isBlank()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        Font font = minecraft.font;
        String text = detail.length() > 80 ? detail.substring(0, 80) : detail;
        float xOffset = -font.width(text) / 2.0F;

        poseStack.pushPose();
        poseStack.translate(
                syna.getX() - camera.x,
                syna.getY() + syna.getBbHeight() + 1.0D - camera.y,
                syna.getZ() - camera.z
        );
        poseStack.mulPose(event.getCamera().rotation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        font.drawInBatch(text, xOffset, 0.0F, 0xE6FFFFFF, false, poseStack.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, 0x66000000, 15728880);
        poseStack.popPose();
        buffer.endBatch();
    }
}