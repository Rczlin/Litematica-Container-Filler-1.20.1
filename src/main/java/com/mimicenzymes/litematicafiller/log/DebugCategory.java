package com.mimicenzymes.litematicafiller.log;

public enum DebugCategory {
    /** PCA sync protocol debug logs */
    PCA,
    /** Fill task lifecycle (start/completed/abort) */
    FILL_TASK,
    /** Phase transitions in the state machine */
    FILL_PHASE,
    /** Detailed performance/hotspot timing */
    PERF,
    /** Area scanner debug logs */
    SCAN
}
