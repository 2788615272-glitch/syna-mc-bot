package com.syna.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.Level;

import java.util.List;

final class SynaFragmentItem extends WrittenBookItem {
    SynaFragmentItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            writePages(stack, serverPlayer.server);
            SynaBoredomDirector.get().showFragment(serverPlayer);
        }
        return super.use(level, player, hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        int mood = tag == null ? 0 : tag.getInt("SynaFragmentMood");
        ChatFormatting color = mood >= 3 ? ChatFormatting.RED
                : mood >= 2 ? ChatFormatting.DARK_RED : ChatFormatting.DARK_PURPLE;
        tooltip.add(Component.literal(FragmentPresentationPolicy.omen(mood))
                .withStyle(color, ChatFormatting.ITALIC));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.getInt("SynaFragmentMood") >= 2) || super.isFoil(stack);
    }

    static void writePages(ItemStack stack) {
        writePages(stack, null);
    }

    static void writePages(ItemStack stack, net.minecraft.server.MinecraftServer server) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrCreateTag();
        int mood = 0;
        if (server != null) {
            SynaBoredomData boredom = SynaBoredomData.get(server);
            mood = FragmentPresentationPolicy.mood(boredom.boredom, boredom.phase,
                    SynaController.get().getHorrorStage());
        }
        String title = FragmentPresentationPolicy.title(mood);
        tag.putInt("SynaFragmentMood", mood);
        tag.putString("title", title);
        tag.putString("author", "Syna");
        tag.putBoolean("resolved", true);
        tag.putInt("generation", 3);
        ChatFormatting titleColor = mood >= 3 ? ChatFormatting.RED
                : mood >= 2 ? ChatFormatting.DARK_RED : ChatFormatting.DARK_PURPLE;
        stack.setHoverName(Component.literal(title).withStyle(titleColor));
        ListTag pages = new ListTag();
        pages.add(page(FragmentPresentationPolicy.omen(mood)));
        pages.add(page("同一件事做得太久，就只剩下声音，没有味道。\n\n她喜欢你换一种答案。"));
        pages.add(page("石头。工具。鲜血。陌生的门。\n\n每一种都能让她多看你一会儿。重复则会让目光变冷。"));
        pages.add(page("取悦只能延后她厌倦世界的时刻，不能让那一刻消失。"));
        pages.add(page("倒计时之后，不要请求宽恕。\n\n听清她定下的规则。只有规则能结束那一轮。"));
        pages.add(page("下一轮里，她会记得你已经用过哪些花样。\n\n—— 有些字像是后来才出现的。"));
        if (server != null) {
            SynaStoryData data = SynaStoryData.get(server);
            String[] fragments = TrueNameMysteryPolicy.fragments(server.overworld().getSeed());
            for (int i = 0; i < data.trueNameClues && i < fragments.length; i++) {
                pages.add(page("真名残段 " + (i + 1) + "/3\n\n" + fragments[i]));
            }
            if (data.trueNameClues >= TrueNameMysteryPolicy.REQUIRED_CLUES) {
                pages.add(page("三个残段必须依次相连。\n\n回到字迹第一次显现的地方，在她追逐你时，手持此页读出：\n\n我以你的真名封印你：<完整名字>"));
            }
        }
        tag.put("pages", pages);
    }

    private static StringTag page(String text) {
        return StringTag.valueOf(Component.Serializer.toJson(Component.literal(text)));
    }
}
