package com.rheinmetal;

import com.rheinmetal.tianshu.foundry.FoundryBootstrap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

@Mod(value = FoundryForgeCompatMod.MOD_ID, dist = Dist.CLIENT)
public class FoundryForgeCompatMod {
    public static final String MOD_ID = FoundryBootstrap.MOD_ID;

    public FoundryForgeCompatMod() {
        FoundryBootstrap.initialize("forge");
    }
}
