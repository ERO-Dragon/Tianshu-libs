package com.rheinmetal;

import com.rheinmetal.tianshu.libs.nativelib.NativeApiSmokeTest;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import net.minecraftforge.fml.common.Mod;

@Mod(TianshuLibsForgeCompatMod.MOD_ID)
public class TianshuLibsForgeCompatMod {
    public static final String MOD_ID = "tianshu_libs";

    public TianshuLibsForgeCompatMod() {
        NativeLibraryLoader.ensureLoaded();
        NativeApiSmokeTest.runOnce();
    }
}
