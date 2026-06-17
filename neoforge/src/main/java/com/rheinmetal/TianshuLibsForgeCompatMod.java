package com.rheinmetal;

import com.rheinmetal.tianshu.libs.TianshuLibsBootstrap;
import net.minecraftforge.fml.common.Mod;

@Mod(TianshuLibsForgeCompatMod.MOD_ID)
public class TianshuLibsForgeCompatMod {
    public static final String MOD_ID = TianshuLibsBootstrap.MOD_ID;

    public TianshuLibsForgeCompatMod() {
        TianshuLibsBootstrap.initialize("forge");
    }
}
