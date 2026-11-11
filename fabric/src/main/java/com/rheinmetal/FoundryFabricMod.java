package com.rheinmetal;

import com.rheinmetal.tianshu.foundry.FoundryBootstrap;
import net.fabricmc.api.ModInitializer;

public class FoundryFabricMod implements ModInitializer {
    public static final String MOD_ID = FoundryBootstrap.MOD_ID;

    @Override
    public void onInitialize() {
        FoundryBootstrap.initialize("fabric");
    }
}
