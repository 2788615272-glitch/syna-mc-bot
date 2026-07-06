package com.syna.bridge.client;

import com.syna.bridge.AliceEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

public class AliceModel extends PlayerModel<AliceEntity> {
    public AliceModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(AliceEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        int horror = entity.getHorrorStage();
        if (horror >= 3) {
            float twitch = Mth.sin(ageInTicks * (horror >= 4 ? 1.7F : 0.9F));
            this.head.zRot = twitch * 0.08F;
            this.rightArm.xRot = -0.08F + twitch * 0.18F;
            this.rightArm.zRot = 0.24F;
            this.leftArm.xRot = -0.08F - twitch * 0.18F;
            this.leftArm.zRot = -0.24F;
            this.rightLeg.xRot *= 0.55F;
            this.leftLeg.xRot *= 0.55F;
        }

        if (!entity.isMiningSwing()) {
            return;
        }

        float cycle = ageInTicks * 0.9F;
        float swingWave = Mth.sin(cycle) * 0.85F;
        float counterWave = Mth.cos(cycle * 0.5F) * 0.15F;

        this.rightArm.xRot = -1.35F + swingWave;
        this.rightArm.yRot = -0.15F;
        this.rightArm.zRot = -0.05F + counterWave;

        this.leftArm.xRot = -0.25F - swingWave * 0.25F;
        this.leftArm.yRot = 0.15F;
        this.leftArm.zRot = -0.1F;
    }
}