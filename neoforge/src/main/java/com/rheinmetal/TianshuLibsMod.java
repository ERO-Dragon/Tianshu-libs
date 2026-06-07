package com.rheinmetal;

import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.rheinmetal.tianshu.libs.nativelib.NativeApiSmokeTest;
import net.neoforged.fml.common.Mod;

@Mod(TianshuLibsMod.MOD_ID)
public class TianshuLibsMod {
    public static final String MOD_ID = "tianshu_libs";

    public TianshuLibsMod() {
        NativeLibraryLoader.ensureLoaded();
        NativeApiSmokeTest.runOnce();
    }
}
