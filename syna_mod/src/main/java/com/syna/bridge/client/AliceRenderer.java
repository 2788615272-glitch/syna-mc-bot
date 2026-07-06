package com.syna.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.syna.bridge.AliceEntity;
import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class AliceRenderer extends HumanoidMobRenderer<AliceEntity, AliceModel> {
    private static final ResourceLocation SYNA_TEXTURE = ResourceLocation.fromNamespaceAndPath(SynaBridgeMod.MOD_ID, "textures/entity/syna-skin.png");

    public AliceRenderer(EntityRendererProvider.Context context) {
        super(context, new AliceModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(AliceEntity entity) {
        return SYNA_TEXTURE;
    }

    @Override
    public void render(AliceEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model.riding = entity.isPassenger();
        this.model.young = entity.isBaby();
        this.model.attackTime = entity.getAttackAnim(partialTick);
        int horror = entity.getHorrorStage();
        if (horror >= 3) {
            poseStack.pushPose();
            float height = horror >= 4 ? 1.82F : 1.42F;
            float width = horror >= 4 ? 0.58F : 0.72F;
            poseStack.scale(width, height, width);
            poseStack.translate(0.0D, 0.08D * horror, 0.0D);
            super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
            poseStack.popPose();
            return;
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }
}