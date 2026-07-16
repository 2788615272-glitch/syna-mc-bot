package com.syna.bridge;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SynaBridgeMod.MOD_ID);

    public static final RegistryObject<Item> SYNA_FRAGMENT = ITEMS.register("syna_fragment",
            () -> new SynaFragmentItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
