package com.rheinmetal;

import com.rheinmetal.tianshu.libs.TianshuLibsBootstrap;
import net.fabricmc.api.ModInitializer;

public class TianshuLibsFabricMod implements ModInitializer {
    public static final String MOD_ID = TianshuLibsBootstrap.MOD_ID;

    @Override
    public void onInitialize() {
        TianshuLibsBootstrap.initialize("fabric");
    }
}
