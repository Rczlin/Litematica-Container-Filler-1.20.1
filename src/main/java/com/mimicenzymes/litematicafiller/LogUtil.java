package com.mimicenzymes.litematicafiller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized debug logging for this mod.
 * All debug output goes through here instead of chat messages or raw System.err.
 */
public class LogUtil {
    public static final Logger LOGGER = LoggerFactory.getLogger(Reference.MOD_ID);

    private static boolean debugMode() {
        return com.mimicenzymes.litematicafiller.config.Configs.DEBUG_MODE.getBooleanValue();
    }

    /** Debug-level log, guarded by DEBUG_MODE config. */
    public static void debug(String format, Object... args) {
        if (debugMode()) {
            LOGGER.debug(String.format(format, args));
        }
    }

    /** Info-level log (always visible). Used for PCA protocol state changes. */
    public static void info(String format, Object... args) {
        LOGGER.info(String.format(format, args));
    }

    /** Warning-level log (always visible). */
    public static void warn(String format, Object... args) {
        LOGGER.warn(String.format(format, args));
    }

    /** Error-level log (always visible). */
    public static void error(String format, Object... args) {
        LOGGER.error(String.format(format, args));
    }
}
