package com.rheinmetal.tianshu.libs;

import com.rheinmetal.tianshu.libs.nativelib.NativeApiSmokeTest;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TianshuLibsBootstrap {
    public static final String MOD_ID = "tianshu_libs";
    public static final String BOOTSTRAP_OK_MARKER = "TIANSHU_LIBS_BOOTSTRAP_OK";

    private static final Logger LOGGER = LoggerFactory.getLogger(TianshuLibsBootstrap.class);
    private static final AtomicBoolean RAN = new AtomicBoolean(false);

    private TianshuLibsBootstrap() {
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
