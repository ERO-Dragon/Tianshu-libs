package com.rheinmetal;

import com.rheinmetal.tianshu.libs.TianshuLibsBootstrap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

@Mod(value = TianshuLibsForgeCompatMod.MOD_ID, dist = Dist.CLIENT)
public class TianshuLibsForgeCompatMod {
    public static final String MOD_ID = TianshuLibsBootstrap.MOD_ID;

    public TianshuLibsForgeCompatMod() {
        TianshuLibsBootstrap.initialize("forge");
    }
}
