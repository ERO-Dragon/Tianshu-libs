package com.rheinmetal;

import com.rheinmetal.tianshu.libs.TianshuLibsBootstrap;
import net.neoforged.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Mod(TianshuLibsMod.MOD_ID)
public class TianshuLibsMod {
    public static final String MOD_ID = TianshuLibsBootstrap.MOD_ID;

    public TianshuLibsMod() {
        if (isClientDist()) {
            TianshuLibsBootstrap.initialize("neoforge");
        }
    }

    private static boolean isClientDist() {
        try {
            Object dist = readFmlLoaderDist();
            return "CLIENT".equals(String.valueOf(dist));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            try {
                Class<?> environmentClass = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
                Object dist = readEnvironmentDist(environmentClass);
                return "CLIENT".equals(String.valueOf(dist));
            } catch (ReflectiveOperationException | LinkageError fallbackIgnored) {
                return false;
            }
        }
    }

    private static Object readFmlLoaderDist() throws ReflectiveOperationException {
        Class<?> loaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
        Method getDist = loaderClass.getMethod("getDist");
        if (Modifier.isStatic(getDist.getModifiers())) {
            return getDist.invoke(null);
        }

        Method getCurrent = loaderClass.getMethod("getCurrent");
        Object currentLoader = getCurrent.invoke(null);
        return getDist.invoke(currentLoader);
    }

    private static Object readEnvironmentDist(Class<?> environmentClass) throws ReflectiveOperationException {
        try {
            Method getDist = environmentClass.getMethod("getDist");
            return getDist.invoke(null);
        } catch (NoSuchMethodException ignored) {
            Field dist = environmentClass.getField("dist");
            return dist.get(null);
        }
    }
}
