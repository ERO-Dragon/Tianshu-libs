package com.rheinmetal;

import com.rheinmetal.tianshu.libs.TianshuLibsBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;

@Mod(TianshuLibsMod.MOD_ID)
public class TianshuLibsMod {
    public static final String MOD_ID = TianshuLibsBootstrap.MOD_ID;

    public TianshuLibsMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            TianshuLibsBootstrap.initialize("neoforge");
        }
    }
}
