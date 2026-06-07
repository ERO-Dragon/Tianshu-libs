package com.rheinmetal;

import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import net.fabricmc.api.ModInitializer;

public class TianshuLibsFabricMod implements ModInitializer {
    public static final String MOD_ID = "tianshu_libs";

    @Override
    public void onInitialize() {
        NativeLibraryLoader.ensureLoaded();
    }
}
