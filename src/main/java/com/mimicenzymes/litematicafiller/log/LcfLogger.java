package com.mimicenzymes.litematicafiller.log;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.config.Configs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Centralized logging for Litematica Container Filler.
 * <p>
 * Rules:
 * <ul>
 *   <li>{@link #debug} / {@link #warn} – only printed when {@code DEBUG_MODE} is ON
 *       <em>and</em> the specific category toggle is ON.</li>
 *   <li>{@link #error} – printed unconditionally (real exceptions).</li>
 * </ul>
 * <p>
 * All format strings use Log4j {@code {}} placeholders (same as {@link Logger}),
 * <strong>not</strong> {@code String.format()} style.
 */
public final class LcfLogger {

    private static final Logger LOGGER = LogManager.getLogger(Reference.MOD_ID);

    private LcfLogger() {}

    // ──────────────────────────────────────────────
    //  Debug (category‑gated)
    // ──────────────────────────────────────────────

    public static void debug(DebugCategory cat, String msg) {
        if (isCategoryActive(cat)) {
            LOGGER.info("[LCF] [{}] {}", cat.name(), msg);
        }
    }

    public static void debug(DebugCategory cat, String format, Object arg1) {
        if (isCategoryActive(cat)) {
            LOGGER.info("[LCF] [{}] " + format, cat.name(), arg1);
        }
    }

    public static void debug(DebugCategory cat, String format, Object arg1, Object arg2) {
        if (isCategoryActive(cat)) {
            LOGGER.info("[LCF] [{}] " + format, cat.name(), arg1, arg2);
        }
    }

    public static void debug(DebugCategory cat, String format, Object arg1, Object arg2, Object arg3) {
        if (isCategoryActive(cat)) {
            LOGGER.info("[LCF] [{}] " + format, cat.name(), arg1, arg2, arg3);
        }
    }

    public static void debug(DebugCategory cat, String format, Object... args) {
        if (isCategoryActive(cat)) {
            Object[] allArgs = new Object[args.length + 1];
            allArgs[0] = cat.name();
            System.arraycopy(args, 0, allArgs, 1, args.length);
            LOGGER.info("[LCF] [{}] " + format, allArgs);
        }
    }

    // ──────────────────────────────────────────────
    //  Warn (category‑gated, same as debug)
    // ──────────────────────────────────────────────

    public static void warn(DebugCategory cat, String msg) {
        if (isCategoryActive(cat)) {
            LOGGER.warn("[LCF] [{}] {}", cat.name(), msg);
        }
    }

    public static void warn(DebugCategory cat, String format, Object arg1) {
        if (isCategoryActive(cat)) {
            LOGGER.warn("[LCF] [{}] " + format, cat.name(), arg1);
        }
    }

    public static void warn(DebugCategory cat, String format, Object arg1, Object arg2) {
        if (isCategoryActive(cat)) {
            LOGGER.warn("[LCF] [{}] " + format, cat.name(), arg1, arg2);
        }
    }

    public static void warn(DebugCategory cat, String format, Object... args) {
        if (isCategoryActive(cat)) {
            Object[] allArgs = new Object[args.length + 1];
            allArgs[0] = cat.name();
            System.arraycopy(args, 0, allArgs, 1, args.length);
            LOGGER.warn("[LCF] [{}] " + format, allArgs);
        }
    }

    // ──────────────────────────────────────────────
    //  Error (unconditional)
    // ──────────────────────────────────────────────

    public static void error(DebugCategory cat, String msg) {
        LOGGER.error("[LCF] [{}] {}", cat.name(), msg);
    }

    public static void error(DebugCategory cat, String format, Object arg1) {
        LOGGER.error("[LCF] [{}] " + format, cat.name(), arg1);
    }

    public static void error(DebugCategory cat, String format, Object arg1, Object arg2) {
        LOGGER.error("[LCF] [{}] " + format, cat.name(), arg1, arg2);
    }

    public static void error(DebugCategory cat, String format, Object... args) {
        Object[] allArgs = new Object[args.length + 1];
        allArgs[0] = cat.name();
        System.arraycopy(args, 0, allArgs, 1, args.length);
        LOGGER.error("[LCF] [{}] " + format, allArgs);
    }

    // ──────────────────────────────────────────────
    //  Legacy compatibility (untyped debug, gated by DEBUG_MODE only)
    // ──────────────────────────────────────────────

    public static void debug(String msg) {
        if (isDebugMode()) {
            LOGGER.info("[LCF] {}", msg);
        }
    }

    public static void debug(String format, Object arg1) {
        if (isDebugMode()) {
            LOGGER.info("[LCF] " + format, arg1);
        }
    }

    public static void debug(String format, Object arg1, Object arg2) {
        if (isDebugMode()) {
            LOGGER.info("[LCF] " + format, arg1, arg2);
        }
    }

    public static void debug(String format, Object... args) {
        if (isDebugMode()) {
            LOGGER.info("[LCF] " + format, args);
        }
    }

    // ──────────────────────────────────────────────
    //  Performance timer helper
    // ──────────────────────────────────────────────

    /**
     * Lightweight {@link AutoCloseable} timer. Use with try‑with‑resources:
     * <pre>{@code
     * try (var t = LcfLogger.timer("someOp")) { ... }
     * }</pre>
     * Prints elapsed time when the block exits (if PERF category is active).
     */
    public abstract static class PerfTimer implements AutoCloseable {
        protected final String label;
        protected final long start;
        protected boolean stopped;

        protected PerfTimer(String label) {
            this.label = label;
            this.start = System.nanoTime();
        }
    }

    private static final class RealPerfTimer extends PerfTimer {
        RealPerfTimer(String label) { super(label); }

        @Override
        public void close() {
            if (stopped) return;
            stopped = true;
            long elapsedUs = (System.nanoTime() - start) / 1000L;
            debug(DebugCategory.PERF, "{} took {}μs ({}ms)", label, elapsedUs, elapsedUs / 1000L);
        }
    }

    private static final class NoopPerfTimer extends PerfTimer {
        NoopPerfTimer() { super(""); }
        @Override public void close() {}
    }

    private static final NoopPerfTimer NOOP_TIMER = new NoopPerfTimer();

    /** Start a named performance timer (gated by PERF category). Returns a no-op timer when PERF is off. */
    public static PerfTimer timer(String label) {
        if (isCategoryActive(DebugCategory.PERF)) {
            return new RealPerfTimer(label);
        }
        return NOOP_TIMER;
    }

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    private static boolean isDebugMode() {
        return Configs.DEBUG_MODE.getBooleanValue();
    }

    private static boolean isCategoryActive(DebugCategory cat) {
        if (!Configs.DEBUG_MODE.getBooleanValue()) return false;
        return switch (cat) {
            case PCA       -> Configs.DEBUG_LOG_PCA.getBooleanValue();
            case FILL_TASK -> Configs.DEBUG_LOG_FILL_TASK.getBooleanValue();
            case FILL_PHASE -> Configs.DEBUG_LOG_FILL_PHASE.getBooleanValue();
            case PERF      -> Configs.DEBUG_LOG_PERF.getBooleanValue();
            case SCAN      -> Configs.DEBUG_LOG_SCAN.getBooleanValue();
        };
    }
}
