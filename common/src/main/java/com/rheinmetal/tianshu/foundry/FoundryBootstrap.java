package com.rheinmetal.tianshu.foundry;

import com.rheinmetal.tianshu.foundry.nativelib.NativeApiSmokeTest;
import com.rheinmetal.tianshu.foundry.nativelib.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FoundryBootstrap {
    public static final String MOD_ID = "tianshu_foundry";
    public static final String BOOTSTRAP_OK_MARKER = "TIANSHU_FOUNDRY_BOOTSTRAP_OK";

    private static final Logger LOGGER = LoggerFactory.getLogger(FoundryBootstrap.class);
    private static final AtomicBoolean RAN = new AtomicBoolean(false);

    private FoundryBootstrap() {
    }

    public static void initialize(String loader) {
        if (!RAN.compareAndSet(false, true)) {
            LOGGER.info("{} loader={} already_initialized=true", BOOTSTRAP_OK_MARKER, loader);
            return;
        }

        NativeLibraryLoader.ensureLoaded();
        NativeApiSmokeTest.runOnce();
        LOGGER.info("{} loader={}", BOOTSTRAP_OK_MARKER, loader);
    }
}
