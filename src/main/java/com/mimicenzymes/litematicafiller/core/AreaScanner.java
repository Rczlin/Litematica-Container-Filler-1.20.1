package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import com.mimicenzymes.litematicafiller.render.HighlightState;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AreaScanner {
    private static final long ATTEMPT_COOLDOWN_MS = 5000L;
    private static final long ATTEMPT_RETENTION_MS = 60000L;
    private static final long READY_PLAN_TTL_MS = 2000L;
    private static final Map<BlockPos, Long> ATTEMPT_COOLDOWNS = new HashMap<>();
    private static final int SILENT_CANDIDATE_BUDGET = 384;
    private static final int PASS_THROUGH_CANDIDATE_BUDGET = 96;
    private static final int MANUAL_CANDIDATE_BUDGET = 2048;
    private static final Object ASYNC_SCAN_LOCK = new Object();
    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LitematicaFiller-AreaScanner");
        thread.setDaemon(true);
        return thread;
    });
    private static int scanCursor = 0;
    private static long scanGeneration = 0L;
    private static boolean scanInFlight = false;
    private static ScanPlan readyPlan = null;

    private static class PendingTask {
        final BlockPos pos;
        final Map<Integer, ItemStack> required;
        final double distSq;

        PendingTask(BlockPos pos, Map<Integer, ItemStack> required, double distSq) {
            this.pos = pos;
            this.required = required;
            this.distSq = distSq;
        }
    }

    private record CandidateSnapshot(BlockPos pos,
                                     Map<Integer, ItemStack> required,
                                     Map<Integer, ItemStack> cachedItems,
                                     Set<Integer> ignoredSlots,
                                     double distSq,
                                     boolean manualNeedsFill) {
    }

    private record ScanSnapshot(List<CandidateSnapshot> candidates,
                                boolean isSilentPrinter,
                                boolean passThroughScan,
                                int maxTasks,
                                UUID playerUuid,
                                RegistryKey<World> dimension,
                                long createdAtMs) {
    }

    private static class ScanPlan {
        final List<PendingTask> pendingTasks;
        final boolean isSilentPrinter;
        final boolean passThroughScan;
        final int maxTasks;
        final UUID playerUuid;
        final RegistryKey<World> dimension;
        final long createdAtMs;
        int nextApplyIndex;

        ScanPlan(List<PendingTask> pendingTasks,
                 boolean isSilentPrinter,
                 boolean passThroughScan,
                 int maxTasks,
                 UUID playerUuid,
                 RegistryKey<World> dimension,
                 long createdAtMs) {
            this.pendingTasks = pendingTasks;
            this.isSilentPrinter = isSilentPrinter;
            this.passThroughScan = passThroughScan;
            this.maxTasks = maxTasks;
            this.playerUuid = playerUuid;
            this.dimension = dimension;
            this.createdAtMs = createdAtMs;
        }

        boolean isCompatible(MinecraftClient client) {
            return client != null
                    && client.player != null
                    && client.world != null
                    && playerUuid.equals(client.player.getUuid())
                    && dimension.equals(client.world.getRegistryKey())
                    && System.currentTimeMillis() - createdAtMs <= READY_PLAN_TTL_MS;
        }

        boolean hasRemainingTasks() {
            return nextApplyIndex < pendingTasks.size();
        }
    }

    public static void executeScan(MinecraftClient mc, boolean isSilentPrinter) {
        executeScan(mc, isSilentPrinter, false);
    }

    public static void executeScan(MinecraftClient mc, boolean isSilentPrinter, boolean passThroughScan) {
        if (mc.player == null || mc.world == null) return;

        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!isSilentPrinter) mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_schematic_world"), true);
            return;
        }

        long now = System.currentTimeMillis();
        pruneAttemptCooldowns(now);
        if (hasReadyPlan() || isScanInFlight()) {
            return;
        }

        ScanSnapshot snapshot = createSnapshot(mc, schematicWorld, now, isSilentPrinter, passThroughScan);
        if (snapshot == null) {
            return;
        }

        submitSnapshot(snapshot);
    }

    public static void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || !Configs.WORKING_STATE.getBooleanValue()
                || SchematicWorldHandler.getSchematicWorld() == null) {
            cancelPendingScan();
            return;
        }

        ScanPlan plan = takeReadyPlan();
        if (plan == null) {
            return;
        }

        if (!plan.isCompatible(mc)) {
            return;
        }

        int added = 0;
        AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
        long now = System.currentTimeMillis();

        while (plan.hasRemainingTasks()) {
            if (!plan.passThroughScan && !filler.canQueueMoreTasks()) {
                break;
            }

            PendingTask task = plan.pendingTasks.get(plan.nextApplyIndex++);
            if (filler.addTask(task.pos, task.required, plan.passThroughScan)) {
                ATTEMPT_COOLDOWNS.put(task.pos, now);
                added++;
                if (added >= plan.maxTasks) {
                    break;
                }
            }
        }

        if (plan.hasRemainingTasks() && plan.isCompatible(mc)) {
            restoreReadyPlan(plan);
            return;
        }

        if (!plan.isSilentPrinter) {
            if (added > 0) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.scan_start", added), true);
            } else {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
            }
        }
    }

    public static void cancelPendingScan() {
        synchronized (ASYNC_SCAN_LOCK) {
            readyPlan = null;
            scanGeneration++;
            scanInFlight = false;
        }
    }

    private static ScanSnapshot createSnapshot(MinecraftClient mc,
                                               net.minecraft.world.World schematicWorld,
                                               long now,
                                               boolean isSilentPrinter,
                                               boolean passThroughScan) {
        BlockPos center = mc.player.getBlockPos();
        int fillRadius = Configs.FILL_RADIUS.getIntegerValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        int maxTasks = passThroughScan ? 4 : (isSilentPrinter ? 15 : 40);
        double reach = Configs.INTERACTION_REACH.getDoubleValue();
        if (reach <= 0.0D) {
            reach = mc.player.isCreative() ? 5.0D : 4.5D;
        }
        double reachSq = (reach + 1.0D) * (reach + 1.0D);
        Vec3d eyePos = mc.player.getEyePos();
        int interactionCandidateRadius = (int) Math.ceil(reach) + 3;
        int candidateRadius = fillRadius > 0 ? fillRadius : interactionCandidateRadius;
        int maxCandidates = passThroughScan ? PASS_THROUGH_CANDIDATE_BUDGET : (isSilentPrinter ? SILENT_CANDIDATE_BUDGET : MANUAL_CANDIDATE_BUDGET);
        List<CandidateSnapshot> candidates = new ArrayList<>();
        Set<BlockPos> processedPositions = new HashSet<>();

        collectCandidates(mc, schematicWorld, center, fillRadius, candidateRadius, maxCandidates, syncLayer,
                eyePos, reachSq, now, isSilentPrinter, passThroughScan, processedPositions, candidates);

        if (candidates.isEmpty() && !isSilentPrinter) {
            mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        return new ScanSnapshot(candidates, isSilentPrinter, passThroughScan, maxTasks,
                mc.player.getUuid(), mc.world.getRegistryKey(), now);
    }

    private static void collectCandidates(MinecraftClient mc,
                                          net.minecraft.world.World schematicWorld,
                                          BlockPos center,
                                          int fillRadius,
                                          int candidateRadius,
                                          int maxCandidates,
                                          boolean syncLayer,
                                          Vec3d eyePos,
                                          double reachSq,
                                          long now,
                                          boolean isSilentPrinter,
                                          boolean passThroughScan,
                                          Set<BlockPos> processedPositions,
                                          List<CandidateSnapshot> candidates) {
        double candidateRadiusSq = (double) candidateRadius * candidateRadius;
        double fillRadiusSq = (double) fillRadius * fillRadius;
        int processedCandidates = 0;

        for (BlockPos pos : HighlightScanner.getHighlights().keySet()) {
            if (pos.getSquaredDistance(center) > candidateRadiusSq) continue;
            if (!collectCandidate(mc, schematicWorld, center, fillRadius, fillRadiusSq, syncLayer, eyePos, reachSq, now,
                    isSilentPrinter, passThroughScan, processedPositions, candidates, pos)) {
                continue;
            }
            if (++processedCandidates >= maxCandidates) return;
        }

        HighlightScanner.ContainerSnapshot snapshot = HighlightScanner.getNearbySchematicContainersSnapshot(center, candidateRadius, scanCursor, maxCandidates);
        scanCursor = snapshot.nextCursor();
        for (BlockPos pos : snapshot.positions()) {
            if (collectCandidate(mc, schematicWorld, center, fillRadius, fillRadiusSq, syncLayer, eyePos, reachSq, now,
                    isSilentPrinter, passThroughScan, processedPositions, candidates, pos)) {
                if (++processedCandidates >= maxCandidates) return;
            }
        }
    }

    private static boolean collectCandidate(MinecraftClient mc,
                                            net.minecraft.world.World schematicWorld,
                                            BlockPos center,
                                            int fillRadius,
                                            double fillRadiusSq,
                                            boolean syncLayer,
                                            Vec3d eyePos,
                                            double reachSq,
                                            long now,
                                            boolean isSilentPrinter,
                                            boolean passThroughScan,
                                            Set<BlockPos> processedPositions,
                                            List<CandidateSnapshot> candidates,
                                            BlockPos rawPos) {
        if (fillRadius > 0 && rawPos.getSquaredDistance(center) > fillRadiusSq) return false;
        if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(rawPos)) return false;

        collectPendingTask(mc, schematicWorld, center, eyePos, reachSq, now, isSilentPrinter, passThroughScan, processedPositions, candidates, rawPos);
        return true;
    }

    private static void collectPendingTask(MinecraftClient mc,
                                           net.minecraft.world.World schematicWorld,
                                           BlockPos center,
                                           Vec3d eyePos,
                                           double reachSq,
                                           long now,
                                           boolean isSilentPrinter,
                                           boolean passThroughScan,
                                           Set<BlockPos> processedPositions,
                                           List<CandidateSnapshot> candidates,
                                           BlockPos rawPos) {
        BlockState state = schematicWorld.getBlockState(rawPos);
        if (state == null || state.isAir() || !state.hasBlockEntity()) return;
        if (!ContainerBlockFilter.isAllowedForSchematicFill(state, schematicWorld, rawPos)) return;

        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, rawPos, state);
        BlockPos taskPos = (halves != null ? halves[0] : rawPos).toImmutable();

        if (!processedPositions.add(taskPos)) return;
        if (ManualContainerOverrideManager.isCompleted(taskPos)) return;
        HighlightState highlightState = HighlightScanner.getHighlights().get(taskPos);
        if (highlightState == HighlightState.SATISFIED || highlightState == HighlightState.MANUAL_COMPLETED) return;
        if (eyePos.squaredDistanceTo(Vec3d.ofCenter(taskPos)) > reachSq) return;
        if (isLoadedRealContainerMissing(mc, taskPos, halves)) return;

        Long lastAttempt = ATTEMPT_COOLDOWNS.get(taskPos);
        long cooldownMs = passThroughScan ? 1200L : ATTEMPT_COOLDOWN_MS;
        if (isSilentPrinter && lastAttempt != null && now - lastAttempt < cooldownMs) {
            return;
        }

        Map<Integer, ItemStack> required = HighlightScanner.getCachedSchematicRequirement(taskPos, mc);
        if (required == null || required.isEmpty()) return;

        Map<Integer, ItemStack> cachedItems = getCachedContainerItemsSnapshot(taskPos, halves);
        Set<Integer> ignoredSlots = HighlightScanner.getCachedIgnoredSlotSnapshot(taskPos, mc);
        candidates.add(new CandidateSnapshot(
                taskPos,
                copyItems(required),
                copyItems(cachedItems),
                ignoredSlots == null ? new HashSet<>() : new HashSet<>(ignoredSlots),
                taskPos.getSquaredDistance(center),
                ManualContainerOverrideManager.isNeedsFill(taskPos)
        ));
    }

    private static boolean isLoadedRealContainerMissing(MinecraftClient mc, BlockPos taskPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            return mc.world.isChunkLoaded(taskPos) &&
                    !ContainerBlockFilter.isAllowedForSchematicFill(mc.world.getBlockState(taskPos), mc.world, taskPos);
        }

        for (BlockPos half : schematicHalves) {
            if (mc.world.isChunkLoaded(half) &&
                    !ContainerBlockFilter.isAllowedForSchematicFill(mc.world.getBlockState(half), mc.world, half)) {
                return true;
            }
        }

        return false;
    }

    public static void clearAttemptCooldown(BlockPos pos) {
        if (pos != null) ATTEMPT_COOLDOWNS.remove(pos);
    }

    private static void submitSnapshot(ScanSnapshot snapshot) {
        final long generation;
        synchronized (ASYNC_SCAN_LOCK) {
            if (scanInFlight || readyPlan != null) {
                return;
            }
            scanInFlight = true;
            generation = ++scanGeneration;
        }

        CompletableFuture
                .supplyAsync(() -> buildPlan(snapshot), SCAN_EXECUTOR)
                .whenComplete((plan, throwable) -> {
                    synchronized (ASYNC_SCAN_LOCK) {
                        if (generation != scanGeneration) {
                            return;
                        }
                        readyPlan = throwable == null ? plan : null;
                        scanInFlight = false;
                    }
                });
    }

    private static ScanPlan buildPlan(ScanSnapshot snapshot) {
        List<PendingTask> pendingTasks = new ArrayList<>();

        for (CandidateSnapshot candidate : snapshot.candidates()) {
            if (!candidate.manualNeedsFill() && isSatisfied(candidate.cachedItems(), candidate.required(), candidate.ignoredSlots())) {
                continue;
            }
            pendingTasks.add(new PendingTask(candidate.pos(), candidate.required(), candidate.distSq()));
        }

        pendingTasks.sort(Comparator.comparingDouble(t -> t.distSq));
        return new ScanPlan(pendingTasks, snapshot.isSilentPrinter(), snapshot.passThroughScan(),
                snapshot.maxTasks(), snapshot.playerUuid(), snapshot.dimension(), snapshot.createdAtMs());
    }

    private static boolean isSatisfied(Map<Integer, ItemStack> cachedItems,
                                       Map<Integer, ItemStack> required,
                                       Set<Integer> ignoredSlots) {
        if (cachedItems == null) return false;

        for (int i = 0; i < 54; i++) {
            if (ignoredSlots != null && ignoredSlots.contains(i)) continue;

            ItemStack real = cachedItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = required.getOrDefault(i, ItemStack.EMPTY);
            if (real.isEmpty() && req.isEmpty()) continue;

            if (real.isEmpty() != req.isEmpty() || !ItemMatcher.isSameItem(real, req) || real.getCount() != req.getCount()) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, ItemStack> getCachedContainerItemsSnapshot(BlockPos taskPos, BlockPos[] halves) {
        Map<Integer, ItemStack> cachedItems = RealContainerCache.getAuthoritativeCachedItems(taskPos);
        if (cachedItems != null) {
            return cachedItems;
        }
        if (halves == null) {
            return null;
        }

        Map<Integer, ItemStack> firstHalf = RealContainerCache.getAuthoritativeCachedItems(halves[0]);
        Map<Integer, ItemStack> secondHalf = RealContainerCache.getAuthoritativeCachedItems(halves[1]);
        return RealContainerCache.combineDoubleContainerItems(firstHalf, secondHalf);
    }

    private static Map<Integer, ItemStack> copyItems(Map<Integer, ItemStack> items) {
        if (items == null) return null;

        Map<Integer, ItemStack> copy = new HashMap<>(items.size());
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    private static void pruneAttemptCooldowns(long now) {
        ATTEMPT_COOLDOWNS.entrySet().removeIf(entry -> now - entry.getValue() > ATTEMPT_RETENTION_MS);
    }

    private static boolean hasReadyPlan() {
        synchronized (ASYNC_SCAN_LOCK) {
            return readyPlan != null;
        }
    }

    private static boolean isScanInFlight() {
        synchronized (ASYNC_SCAN_LOCK) {
            return scanInFlight;
        }
    }

    private static ScanPlan takeReadyPlan() {
        synchronized (ASYNC_SCAN_LOCK) {
            ScanPlan plan = readyPlan;
            readyPlan = null;
            return plan;
        }
    }

    private static void restoreReadyPlan(ScanPlan plan) {
        synchronized (ASYNC_SCAN_LOCK) {
            if (readyPlan == null) {
                readyPlan = plan;
            }
        }
    }
}
