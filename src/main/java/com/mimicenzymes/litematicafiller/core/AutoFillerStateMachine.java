package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import com.mimicenzymes.litematicafiller.dependency.DummyExtractor;
import com.mimicenzymes.litematicafiller.dependency.IShulkerExtractor;
import com.mimicenzymes.litematicafiller.dependency.QuickShulkerWrapper;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AutoFillerStateMachine {

    public enum Phase {
        IDLE,
        AWAITING_DATA,
        INSPECTING,
        STASHING,
        GATHERING,
        FILLING,
        RETURNING
    }

    public static class FillTask {
        public final BlockPos targetPos;
        public final Map<Integer, ItemStack> requiredItems;
        public Map<Integer, ItemStack> missingItems;
        public boolean needsInspection;

        public FillTask(BlockPos targetPos, Map<Integer, ItemStack> requiredItems, Map<Integer, ItemStack> missingItems, boolean needsInspection) {
            this.targetPos = targetPos;
            this.requiredItems = requiredItems;
            this.missingItems = missingItems;
            this.needsInspection = needsInspection;
        }
    }

    private static final AutoFillerStateMachine INSTANCE = new AutoFillerStateMachine();
    public static AutoFillerStateMachine getInstance() { return INSTANCE; }

    private final Queue<FillTask> taskQueue = new ConcurrentLinkedQueue<>();
    private FillTask currentTask = null;
    private SlotMapper currentMapper = null;

    private Phase currentPhase = Phase.IDLE;
    private final Deque<Runnable> actionQueue = new LinkedList<>();
    private final Queue<Integer> pendingShulkers = new LinkedList<>();

    private int actionWaitTicks = 0;
    private int watchdogTimer = 0;
    private int uiWaitTimer = 0;
    private int dataWaitTimer = 0;
    private boolean silentlyExtracting = false;
    private boolean yieldTick = false;

    private int activeShulkerSlot = -1;
    private int stashShulkerSlot = -1;
    private int stashItemSlot = -1;

    private final Set<Item> borrowedItems = new HashSet<>();
    private final Map<Item, Integer> stashedItemCounts = new HashMap<>();

    private final Set<Integer> openedShulkerSlots = new LinkedHashSet<>();
    private final Map<Integer, Set<Item>> shulkerMisses = new HashMap<>();
    private final Map<BlockPos, Set<Item>> failedContainers = new ConcurrentHashMap<>();

    private boolean lastContinuousState = false;
    private int tickCounter = 0;
    private int consecutiveFailures = 0;
    private final IShulkerExtractor shulkerExtractor;

    private static java.lang.reflect.Field CACHE_FIELD = null;
    private static java.lang.reflect.Field NBT_QUERY_CACHE_FIELD = null;
    static {
        try {
            CACHE_FIELD = RealContainerCache.class.getDeclaredField("CACHE");
            CACHE_FIELD.setAccessible(true);
            NBT_QUERY_CACHE_FIELD = RealContainerCache.class.getDeclaredField("NBT_QUERY_CACHE");
            NBT_QUERY_CACHE_FIELD.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private AutoFillerStateMachine() {
        this.shulkerExtractor = DependencyChecker.HAS_QUICK_SHULKER ? new QuickShulkerWrapper() : new DummyExtractor();
    }

    private int getDelay(int baseTicks) {
        if (!Configs.ENABLE_SAFETY_DELAY.getBooleanValue()) {
            return 0;
        }
        return baseTicks + Configs.FILL_DELAY.getIntegerValue();
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, ItemStack> getReliableCache(BlockPos pos) {
        try {
            if (CACHE_FIELD != null) {
                Map<BlockPos, Map<Integer, ItemStack>> cache = (Map<BlockPos, Map<Integer, ItemStack>>) CACHE_FIELD.get(null);
                if (cache.containsKey(pos)) return cache.get(pos);
            }
            if (NBT_QUERY_CACHE_FIELD != null) {
                Map<BlockPos, Map<Integer, ItemStack>> nbtCache = (Map<BlockPos, Map<Integer, ItemStack>>) NBT_QUERY_CACHE_FIELD.get(null);
                if (nbtCache.containsKey(pos)) return nbtCache.get(pos);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Map<Integer, ItemStack> getTrueContainerData(MinecraftClient client, BlockPos pos) {

        pos = pos.toImmutable();
        final BlockPos finalPos = pos;

        Map<Integer, ItemStack> verifiedCache = getReliableCache(finalPos);
        if (verifiedCache != null) {
            return verifiedCache;
        }

        if (client.isInSingleplayer() && client.getServer() != null && client.world != null && client.player != null) {

            ServerPlayerEntity serverPlayer = client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
            if (serverPlayer != null) {
                ServerWorld serverWorld = (ServerWorld) serverPlayer.getEntityWorld();

                if (serverWorld != null) {
                    net.minecraft.block.BlockState clientState = client.world.getBlockState(finalPos);
                    final BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, finalPos, clientState);

                    client.getServer().execute(() -> {
                        net.minecraft.block.BlockState state = serverWorld.getBlockState(finalPos);
                        Map<Integer, ItemStack> inventoryData = null;

                        if (halves != null && state.isOf(net.minecraft.block.Blocks.BARREL)) {
                            Map<Integer, ItemStack> right = getSingleBlockEntityInventory(serverWorld, halves[0]);
                            Map<Integer, ItemStack> left = getSingleBlockEntityInventory(serverWorld, halves[1]);
                            if (right != null && left != null) {
                                Map<Integer, ItemStack> combined = new HashMap<>(right);
                                left.forEach((k, v) -> combined.put(k + 27, v));
                                inventoryData = combined;
                            }
                        } else {
                            inventoryData = getSingleBlockEntityInventory(serverWorld, finalPos);
                        }

                        if (inventoryData != null) {
                            if (halves != null) {
                                RealContainerCache.put(halves[0].toImmutable(), inventoryData);
                                RealContainerCache.put(halves[1].toImmutable(), inventoryData);
                            } else {
                                RealContainerCache.put(finalPos, inventoryData);
                            }
                        }

                        if (state.getBlock() instanceof net.minecraft.block.CrafterBlock) {
                            net.minecraft.block.entity.BlockEntity be = serverWorld.getBlockEntity(finalPos);
                            if (be != null) {
                                net.minecraft.nbt.NbtCompound nbt = be.createNbt(serverWorld.getRegistryManager());
                                Set<Integer> locks = RealContainerCache.parseDisabledSlots(nbt);
                                RealContainerCache.putLock(finalPos, locks);
                            }
                        }
                    });
                }
            }
        }

        Map<Integer, ItemStack> fallback = RealContainerCache.getCachedItems(pos);

        if (fallback != null && fallback.isEmpty()) {
            return null;
        }

        return fallback;
    }

    private Map<Integer, ItemStack> getSingleBlockEntityInventory(net.minecraft.server.world.ServerWorld world, BlockPos pos) {
        net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);

        if (be == null) {
            return null;
        }

        net.minecraft.inventory.Inventory inv = null;
        net.minecraft.block.BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof net.minecraft.block.ChestBlock chest) {
            inv = net.minecraft.block.ChestBlock.getInventory(
                    chest,
                    state,
                    world,
                    pos,
                    true
            );
        }

        if (inv == null && be instanceof net.minecraft.inventory.Inventory inventory) {
            inv = inventory;
        }

        if (inv != null) {
            Map<Integer, ItemStack> map = new HashMap<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    map.put(i, stack.copy());
                }
            }
            return map;
        }

        net.minecraft.nbt.NbtCompound nbt = be.createNbt(world.getRegistryManager());

        if (nbt != null && nbt.contains("Items")) {
            return RealContainerCache.parseNbtInventory(nbt, world.getRegistryManager());
        }

        return null;
    }

    private Map<Integer, ItemStack> inventoryToMap(net.minecraft.inventory.Inventory inv) {
        Map<Integer, ItemStack> map = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty()) {
                map.put(i, stack.copy());
            }
        }
        return map;
    }

    private static int transactionIdCounter = 0;
    private void requestNbtUpdate(MinecraftClient client, BlockPos pos) {
        if (!client.isInSingleplayer() && client.player != null && client.player.hasPermissionLevel(2)) {
            try {
                if (Configs.ENABLE_OP_NBT_QUERY.getBooleanValue() && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket(transactionIdCounter++, pos));
                }
            } catch (Exception ignored) {}
        }
    }

    public void addTask(BlockPos pos, Map<Integer, ItemStack> requiredItems) {
        if (requiredItems == null || requiredItems.isEmpty()) {
            return;
        }

        if (failedContainers.containsKey(pos)) return;
        if (currentTask != null && currentTask.targetPos.equals(pos)) return;
        for (FillTask t : taskQueue) {
            if (t.targetPos.equals(pos)) return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Map<Integer, ItemStack> trueData = getTrueContainerData(client, pos);

        if (trueData == null) {
            taskQueue.add(new FillTask(pos, requiredItems, new HashMap<>(), true));
            requestNbtUpdate(client, pos);
            return;
        }

        boolean isCrafter = client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock;
        boolean needsAction = false;
        Map<Integer, ItemStack> missingItems = new HashMap<>();

        for (int i = 0; i < 54; i++) {
            ItemStack req = requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (req.isEmpty() && cur.isEmpty()) continue;

            if (req.isEmpty() && !cur.isEmpty()) {
                needsAction = true;
            } else if (!req.isEmpty() && cur.isEmpty()) {
                needsAction = true;
                missingItems.put(i, req.copy());
            } else if (!ItemMatcher.isSameItem(req, cur)) {
                needsAction = true;
                missingItems.put(i, req.copy());
            } else if (cur.getCount() < req.getCount()) {
                needsAction = true;
                ItemStack diff = req.copy();
                diff.setCount(req.getCount() - cur.getCount());
                missingItems.put(i, diff);
            } else if (cur.getCount() > req.getCount()) {
                if (!isCrafter) needsAction = true;
            }
        }

        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            needsAction = true;
        }

        if (!needsAction) return;

        taskQueue.add(new FillTask(pos, requiredItems, missingItems, false));
    }

    private boolean checkMaterialsAndPrepare(MinecraftClient client) {
        if (currentTask.missingItems.isEmpty()) return true;

        int emptySlots = getEmptySlots(client).size();
        if (emptySlots == 0) {
            boolean hasItemsToFill = false;
            for (ItemStack req : currentTask.missingItems.values()) {
                if (countItemInPlayerInv(client, req) > 0) {
                    hasItemsToFill = true;
                    break;
                }
            }
            if (!hasItemsToFill) {
                if (!Configs.AUTO_STASH_ITEMS.getBooleanValue() || findStashAction(client, currentTask.missingItems.values()) == null) {
                    // 如果丢弃开关被开启，允许即使无格子也能强行启动（利用掉落化解死锁）
                    if (!Configs.DROP_EXTRACTED_ITEMS.getBooleanValue()) {
                        sendFeedback(client, Text.translatable("litematica_container_filler.message.inventory_full_no_stash").getString(), true);
                        return false;
                    }
                }
            }
        }

        boolean hasAtLeastOneMaterial = false;
        Set<Item> missingTypes = new LinkedHashSet<>();
        for (ItemStack req : currentTask.missingItems.values()) {
            if (hasItemAnywhere(client, req)) {
                hasAtLeastOneMaterial = true;
            } else {
                missingTypes.add(req.getItem());
            }
        }

        if (!hasAtLeastOneMaterial) {
            failedContainers.put(currentTask.targetPos, missingTypes);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Item item : missingTypes) {
                if (count > 0) sb.append(", ");
                sb.append(item.getName().getString());
                count++;
                if (count >= 3 && missingTypes.size() > 3) {
                    sb.append(Text.translatable("litematica_container_filler.message.etc").getString());
                    break;
                }
            }
            sendFeedback(client, Text.translatable("litematica_container_filler.message.material_shortage", sb.toString()).getString(), true);
            return false;
        }

        sendFeedback(client, Text.translatable("litematica_container_filler.message.task_dispatched").getString(), true);
        return true;
    }

    private void abortTask(MinecraftClient client, String errorMsgKey) {
        sendFeedback(client, Text.translatable(errorMsgKey).getString(), true);
        actionQueue.add(() -> {
            if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
                client.player.closeHandledScreen();
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(this::reset);
    }

    private List<Integer> getEmptySlots(MinecraftClient client) {
        List<Integer> emptySlots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isEmpty()) {
                emptySlots.add(i);
            }
        }
        return emptySlots;
    }

    private int[] findStashAction(MinecraftClient client, Collection<ItemStack> requiredValues) {
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return null;

        int targetShulker = -1;
        int itemToStash = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
                long size = c == null ? 0 : c.stream().filter(stack -> !stack.isEmpty()).count();
                if (size < 27) {
                    targetShulker = i;
                    break;
                }
            }
        }

        if (targetShulker == -1) return null;

        for (int i = 0; i < 36; i++) {
            if (i == targetShulker) continue;
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) continue;

            boolean isNeeded = false;
            for (ItemStack req : requiredValues) {
                if (ItemMatcher.isSameItem(s, req)) {
                    isNeeded = true; break;
                }
            }
            if (!isNeeded) {
                itemToStash = i;
                break;
            }
        }

        if (itemToStash != -1) {
            return new int[]{targetShulker, itemToStash};
        }
        return null;
    }

    public boolean isSilentlyExtracting() { return silentlyExtracting; }
    public void clearBlacklist() { failedContainers.clear(); }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { reset(); return; }

        boolean currentContinuousState = Configs.CONTINUOUS_FILL.getBooleanValue();
        if (currentContinuousState != lastContinuousState) {
            clearBlacklist();
            if (!currentContinuousState) taskQueue.clear();
            lastContinuousState = currentContinuousState;
        }

        tickCounter++;
        if (!failedContainers.isEmpty() && tickCounter % 10 == 0) {
            failedContainers.entrySet().removeIf(entry -> {
                for (Item item : entry.getValue()) {
                    if (hasItemAnywhere(client, item.getDefaultStack())) return true;
                }
                return false;
            });
        }

        if (currentTask != null) {
            watchdogTimer++;
            if (watchdogTimer > 150) {
                abortTask(client, "litematica_container_filler.message.timeout_reset");
                return;
            }
        }

        if (actionWaitTicks > 0) { actionWaitTicks--; watchdogTimer = 0; return; }

        if (currentTask == null) {
            if (!taskQueue.isEmpty()) {
                currentTask = taskQueue.poll();
                borrowedItems.clear();
                openedShulkerSlots.clear();
                shulkerMisses.clear();
                consecutiveFailures = 0;

                if (currentTask.needsInspection) {
                    currentPhase = Phase.AWAITING_DATA;
                    dataWaitTimer = 0;
                } else {
                    if (!checkMaterialsAndPrepare(client)) {
                        reset(); return;
                    }
                    checkAndStartGatheringOrFilling(client);
                }
            } else {
                return;
            }
        }

        yieldTick = false;
        while (!actionQueue.isEmpty() && actionWaitTicks <= 0 && !yieldTick) {
            actionQueue.poll().run();
            if (!yieldTick) watchdogTimer = 0;
        }

        if (actionWaitTicks <= 0 && actionQueue.isEmpty() && currentTask != null && !yieldTick) {
            ScreenHandler currentHandler = client.player.currentScreenHandler;
            boolean inGui = currentHandler != client.player.playerScreenHandler;

            switch (currentPhase) {
                case AWAITING_DATA:
                    Map<Integer, ItemStack> lateCache = getTrueContainerData(client, currentTask.targetPos);
                    if (lateCache != null) {
                        currentTask.needsInspection = false;

                        boolean isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
                        boolean needsAction = false;
                        Map<Integer, ItemStack> missingItems = new HashMap<>();

                        for (int i = 0; i < 54; i++) {
                            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                            ItemStack cur = lateCache.getOrDefault(i, ItemStack.EMPTY);

                            if (req.isEmpty() && cur.isEmpty()) continue;
                            if (req.isEmpty() && !cur.isEmpty()) needsAction = true;
                            else if (!req.isEmpty() && cur.isEmpty()) {
                                needsAction = true;
                                missingItems.put(i, req.copy());
                            } else if (!ItemMatcher.isSameItem(req, cur)) {
                                needsAction = true;
                                missingItems.put(i, req.copy());
                            } else if (cur.getCount() < req.getCount()) {
                                needsAction = true;
                                ItemStack diff = req.copy();
                                diff.setCount(req.getCount() - cur.getCount());
                                missingItems.put(i, diff);
                            } else if (cur.getCount() > req.getCount()) {
                                if (!isCrafter) needsAction = true;
                            }
                        }

                        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) {
                            needsAction = true;
                        }

                        if (!needsAction) {
                            sendFeedback(client, Text.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                            reset();
                            break;
                        }

                        currentTask.missingItems.clear();
                        currentTask.missingItems.putAll(missingItems);

                        if (!checkMaterialsAndPrepare(client)) {
                            reset(); break;
                        }
                        checkAndStartGatheringOrFilling(client);
                    } else {
                        dataWaitTimer++;
                        if (dataWaitTimer > 20) {
                            currentPhase = Phase.INSPECTING;
                        }
                    }
                    break;

                case INSPECTING:
                    if (!inGui) {
                        openTargetContainer(client, currentTask.targetPos);
                    } else {
                        if (!silentlyExtracting) doInspectionPhase(client);
                    }
                    break;

                case STASHING:
                    if (!inGui) {
                        openShulkerBox(client, stashShulkerSlot);
                    } else {
                        if (silentlyExtracting) doStashPhase(client);
                    }
                    break;

                case GATHERING:
                    if (!inGui) {
                        if (pendingShulkers.isEmpty()) {
                            currentPhase = Phase.FILLING;
                        } else {
                            int slot = pendingShulkers.poll();
                            openShulkerBox(client, slot);
                        }
                    } else {
                        if (silentlyExtracting) doShulkerExtractionPhase(client);
                    }
                    break;

                case FILLING:
                    if (!inGui) {
                        openTargetContainer(client, currentTask.targetPos);
                    } else {
                        if (!silentlyExtracting) {
                            if (currentMapper == null) {
                                currentMapper = new SlotMapper(currentHandler, client.player.getInventory());
                            }
                            executeBurstFill(client, currentHandler);
                        }
                    }
                    break;

                case RETURNING:
                    if (!inGui) {
                        if (pendingShulkers.isEmpty()) {
                            reset();
                        } else {
                            int slot = pendingShulkers.poll();
                            openShulkerBox(client, slot);
                        }
                    } else {
                        if (silentlyExtracting) returnBorrowedAndStashedItems(client);
                    }
                    break;

                case IDLE:
                    break;
            }
        }
    }

    private void doInspectionPhase(MinecraftClient client) {
        actionQueue.add(() -> client.player.closeHandledScreen());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> {
            currentTask.needsInspection = false;
            Map<Integer, ItemStack> newlyCached = getReliableCache(currentTask.targetPos);
            if (newlyCached == null) newlyCached = new HashMap<>();

            boolean isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
            boolean needsAction = false;
            Map<Integer, ItemStack> missingItems = new HashMap<>();

            for (int i = 0; i < 54; i++) {
                ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                ItemStack cur = newlyCached.getOrDefault(i, ItemStack.EMPTY);

                if (req.isEmpty() && cur.isEmpty()) continue;
                if (req.isEmpty() && !cur.isEmpty()) needsAction = true;
                else if (!req.isEmpty() && cur.isEmpty()) {
                    needsAction = true;
                    missingItems.put(i, req.copy());
                } else if (!ItemMatcher.isSameItem(req, cur)) {
                    needsAction = true;
                    missingItems.put(i, req.copy());
                } else if (cur.getCount() < req.getCount()) {
                    needsAction = true;
                    ItemStack diff = req.copy();
                    diff.setCount(req.getCount() - cur.getCount());
                    missingItems.put(i, diff);
                } else if (cur.getCount() > req.getCount()) {
                    if (!isCrafter) needsAction = true;
                }
            }

            if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) {
                needsAction = true;
            }

            if (!needsAction) {
                sendFeedback(client, Text.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                reset();
            } else {
                currentTask.missingItems.clear();
                currentTask.missingItems.putAll(missingItems);
                if (checkMaterialsAndPrepare(client)) {
                    checkAndStartGatheringOrFilling(client);
                } else {
                    reset();
                }
            }
        });
    }

    private void checkAndStartGatheringOrFilling(MinecraftClient client) {
        if (currentTask.missingItems.isEmpty()) {
            currentPhase = Phase.FILLING;
            return;
        }

        int emptySlots = getEmptySlots(client).size();
        if (emptySlots == 0 && Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                stashShulkerSlot = stashAction[0];
                stashItemSlot = stashAction[1];
                currentPhase = Phase.STASHING;
                return;
            }
        }

        pendingShulkers.clear();
        List<ItemStack> needed = computeNeededToFetch(client);
        if (!needed.isEmpty()) {
            Set<Integer> shulkers = findShulkersContaining(client, needed);
            if (!shulkers.isEmpty()) {
                pendingShulkers.addAll(shulkers);
                currentPhase = Phase.GATHERING;
                return;
            } else {
                if (hasAnyMaterialsToFill(client)) {
                    currentPhase = Phase.FILLING;
                    return;
                }
                abortTask(client, "litematica_container_filler.message.materials_depleted");
                return;
            }
        }

        currentPhase = Phase.FILLING;
    }

    private boolean hasAnyMaterialsToFill(MinecraftClient client) {
        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!req.isEmpty()) {
                int missing = req.getCount() - (ItemMatcher.isSameItem(req, cur) ? cur.getCount() : 0);
                if (missing > 0 && countItemInPlayerInv(client, req) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ItemStack> computeNeededToFetch(MinecraftClient client) {
        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        List<ItemStack> toFetch = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!req.isEmpty()) {
                if (cur.isEmpty() || !ItemMatcher.isSameItem(req, cur)) {
                    toFetch.add(req.copy());
                } else if (cur.getCount() < req.getCount()) {
                    ItemStack diff = req.copy();
                    diff.setCount(req.getCount() - cur.getCount());
                    toFetch.add(diff);
                }
            }
        }

        List<ItemStack> consolidated = new ArrayList<>();
        for (ItemStack req : toFetch) {
            boolean found = false;
            for (ItemStack exist : consolidated) {
                if (ItemMatcher.isSameItem(exist, req)) {
                    exist.setCount(exist.getCount() + req.getCount());
                    found = true; break;
                }
            }
            if (!found) consolidated.add(req.copy());
        }

        List<ItemStack> needed = new ArrayList<>();
        for (ItemStack req : consolidated) {
            int inInv = countItemInPlayerInv(client, req);
            if (inInv < req.getCount()) {
                ItemStack diff = req.copy();
                diff.setCount(req.getCount() - inInv);
                needed.add(diff);
            }
        }
        return needed;
    }

    private void doStashPhase(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        int uiSlot = stashItemSlot < 9 ? stashItemSlot + 54 : stashItemSlot + 18;

        ItemStack stackToStash = h.slots.get(uiSlot).getStack();
        if (!stackToStash.isEmpty()) {
            Item stashedItem = stackToStash.getItem();
            stashedItemCounts.put(stashedItem, stashedItemCounts.getOrDefault(stashedItem, 0) + stackToStash.getCount());
        }

        client.interactionManager.clickSlot(h.syncId, uiSlot, 0, SlotActionType.QUICK_MOVE, client.player);
        sendFeedback(client, Text.translatable("litematica_container_filler.message.stashing_items").getString(), true);

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            silentlyExtracting = false;
            activeShulkerSlot = -1;
            stashShulkerSlot = -1;
            stashItemSlot = -1;
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> checkAndStartGatheringOrFilling(client));
    }

    private Set<Integer> findShulkersContaining(MinecraftClient client, List<ItemStack> needed) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return slots;

        for (ItemStack req : needed) {
            int amountToFind = req.getCount();
            for (int i = 0; i < 36; i++) {
                if (amountToFind <= 0) break;
                if (shulkerMisses.containsKey(i) && shulkerMisses.get(i).contains(req.getItem())) continue;

                ItemStack s = client.player.getInventory().getStack(i);
                if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                    ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
                    if (c != null) {
                        for (ItemStack inner : c.stream().toList()) {
                            if (ItemMatcher.isSameItem(inner, req)) {
                                slots.add(i);
                                amountToFind -= inner.getCount();
                            }
                        }
                    }
                }
            }
        }
        return slots;
    }

    private void openTargetContainer(MinecraftClient client, BlockPos pos) {
        silentlyExtracting = false;
        BlockHitResult hitResult = new BlockHitResult(new Vec3d(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        currentMapper = null;
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void openShulkerBox(MinecraftClient client, int slot) {
        silentlyExtracting = true;
        activeShulkerSlot = slot;
        openedShulkerSlots.add(slot);

        if (currentPhase == Phase.STASHING) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_stash").getString(), true);
        } else if (currentPhase == Phase.RETURNING) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_return").getString(), true);
        } else {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_extract").getString(), true);
        }

        actionQueue.add(() -> shulkerExtractor.requestOpenShulker(slot));
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void doShulkerExtractionPhase(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        Set<Integer> usedEmptySlots = new HashSet<>();
        List<ItemStack> needed = computeNeededToFetch(client);

        int remainingEmptySlots = getEmptySlots(client).size();
        Map<Item, Integer> partialSpaces = new HashMap<>();

        for (int i = 0; i < 36; i++) {
            ItemStack invStack = client.player.getInventory().getStack(i);
            if (!invStack.isEmpty()) {
                partialSpaces.put(invStack.getItem(), partialSpaces.getOrDefault(invStack.getItem(), 0) + (invStack.getMaxCount() - invStack.getCount()));
            }
        }

        boolean inventoryFull = false;

        for (ItemStack req : needed) {
            if (inventoryFull) break;

            if (remainingEmptySlots <= 0 && partialSpaces.getOrDefault(req.getItem(), 0) <= 0) {
                continue;
            }

            int amountMissing = req.getCount();
            int amountTaken = 0;

            for (int i = 0; i < h.slots.size() - 36; i++) {
                if (amountTaken >= amountMissing) break;

                ItemStack slotStack = h.slots.get(i).getStack();
                if (slotStack.isEmpty() || !ItemMatcher.isSameItem(slotStack, req)) continue;

                int partialSpace = partialSpaces.getOrDefault(req.getItem(), 0);
                if (remainingEmptySlots <= 0 && partialSpace <= 0) {
                    inventoryFull = true;
                    break;
                }

                int amountAvailable = slotStack.getCount();
                int maxWeCanTake = amountAvailable;
                if (remainingEmptySlots <= 0) {
                    maxWeCanTake = Math.min(amountAvailable, partialSpace);
                }

                int amountToTake = Math.min(amountMissing - amountTaken, maxWeCanTake);
                if (amountToTake <= 0) continue;

                if (amountToTake == amountAvailable) {
                    client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                } else {
                    int emptySlot = -1;
                    for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                        if (h.slots.get(j).getStack().isEmpty() && !usedEmptySlots.contains(j)) {
                            emptySlot = j; break;
                        }
                    }
                    if (emptySlot != -1) {
                        usedEmptySlots.add(emptySlot);
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                        for (int k = 0; k < amountToTake; k++) {
                            client.interactionManager.clickSlot(h.syncId, emptySlot, 1, SlotActionType.PICKUP, client.player);
                        }
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                    } else {
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                    }
                }

                borrowedItems.add(req.getItem());
                amountTaken += amountToTake;

                if (amountToTake > partialSpace) {
                    remainingEmptySlots--;
                    int overflow = amountToTake - partialSpace;
                    partialSpaces.put(req.getItem(), req.getMaxCount() - overflow);
                } else {
                    partialSpaces.put(req.getItem(), partialSpace - amountToTake);
                }
            }

            if (amountTaken == 0 && (remainingEmptySlots > 0 || partialSpaces.getOrDefault(req.getItem(), 0) > 0)) {
                shulkerMisses.computeIfAbsent(activeShulkerSlot, k -> new HashSet<>()).add(req.getItem());
            }
        }

        sendFeedback(client, Text.translatable("litematica_container_filler.message.extraction_done").getString(), true);
        final boolean forceDump = (remainingEmptySlots <= 0);

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            silentlyExtracting = false;
            activeShulkerSlot = -1;

            if (forceDump) {
                pendingShulkers.clear();
                currentPhase = Phase.FILLING;
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private boolean canAbsorb(MinecraftClient client, ItemStack stack) {
        if (getEmptySlots(client).size() > 0) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack pStack = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(pStack, stack) && pStack.getCount() + stack.getCount() <= pStack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private void triggerStashOrAbort(MinecraftClient client) {
        if (Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                actionQueue.add(() -> client.player.closeHandledScreen());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> {
                    stashShulkerSlot = stashAction[0];
                    stashItemSlot = stashAction[1];
                    currentPhase = Phase.STASHING;
                });
                return;
            }
        }
        abortTask(client, "litematica_container_filler.message.inventory_full_cannot_extract");
    }

    private void executeBurstFill(MinecraftClient client, ScreenHandler handler) {
        int syncId = handler.syncId;
        int delay = Configs.ENABLE_SAFETY_DELAY.getBooleanValue() ? Configs.FILL_DELAY.getIntegerValue() : 0;

        boolean dropExtracted = Configs.DROP_EXTRACTED_ITEMS.getBooleanValue();

        if (!handler.getCursorStack().isEmpty()) {
            if (!tryPlaceCursorItem(client, handler)) {
                if (dropExtracted) {
                    client.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, client.player);
                } else {
                    abortTask(client, "litematica_container_filler.message.cursor_stuck");
                    return;
                }
            }
            if (delay > 0) { actionWaitTicks = delay; return; }
        }

        if (handler instanceof CrafterScreenHandler crafterHandler && client.currentScreen instanceof HandledScreen<?> handledScreen) {
            Set<Integer> targetDisabled = LitematicaContainerReader.getDisabledSlots(currentTask.targetPos);
            boolean toggledInThisTick = false;
            for (int i = 0; i < 9; i++) {
                boolean shouldBeDisabled = targetDisabled != null && targetDisabled.contains(i);
                if (shouldBeDisabled != crafterHandler.isSlotDisabled(i)) {
                    if (crafterHandler.getSlot(i).hasStack()) {
                        if (dropExtracted) {
                            simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 1, SlotActionType.THROW);
                        } else {
                            simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 0, SlotActionType.QUICK_MOVE);
                        }
                    } else {
                        simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 0, SlotActionType.PICKUP);
                    }
                    toggledInThisTick = true;
                    if (delay > 0) break;
                }
            }
            if (toggledInThisTick) { actionWaitTicks = delay; if (delay > 0) return; }
        }

        int containerSize = handler.slots.size() - 36;
        if (handler instanceof CrafterScreenHandler) containerSize = 9;
        if (containerSize <= 0) { finishTaskAndReturn(client); return; }

        boolean movedAny = false;
        boolean stillNeedsAction = false;
        boolean swappedAnyInThisPass = false;
        boolean extractedAnyInThisPass = false;

        for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
            if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

            ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
            int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
            ItemStack curStack = handler.slots.get(uiSlot).getStack();

            if (!reqStack.isEmpty() && !curStack.isEmpty() && !ItemMatcher.isSameItem(reqStack, curStack)) {
                stillNeedsAction = true;

                int playerSlot = findItemInPlayerInv(client, reqStack);
                if (playerSlot != -1) {
                    int uiPlayerSlot = currentMapper.getUiSlotForPlayer(playerSlot);

                    client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(syncId, uiSlot, 0, SlotActionType.PICKUP, client.player);

                    if (dropExtracted) {
                        client.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, client.player);
                    } else {
                        client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
                    }

                    movedAny = true;
                    swappedAnyInThisPass = true;
                    if (delay > 0) break;
                }
            }
        }

        if (!swappedAnyInThisPass || delay == 0) {
            for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
                ItemStack curStack = handler.slots.get(uiSlot).getStack();

                if (curStack.isEmpty()) continue;

                boolean isWrong = !reqStack.isEmpty() && !ItemMatcher.isSameItem(curStack, reqStack);
                boolean isExcess = ItemMatcher.isSameItem(curStack, reqStack) && curStack.getCount() > reqStack.getCount();
                if (handler instanceof CrafterScreenHandler) isExcess = false;

                if (reqStack.isEmpty() || isWrong || isExcess) {
                    stillNeedsAction = true;

                    if (dropExtracted) {
                        client.interactionManager.clickSlot(syncId, uiSlot, 1, SlotActionType.THROW, client.player);
                    } else {
                        if (!canAbsorb(client, curStack)) {
                            triggerStashOrAbort(client);
                            return;
                        }
                        client.interactionManager.clickSlot(syncId, uiSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                    }

                    movedAny = true;
                    extractedAnyInThisPass = true;
                    if (delay > 0) break;
                }
            }
        }

        if ((!swappedAnyInThisPass && !extractedAnyInThisPass) || delay == 0) {
            for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
                ItemStack curStack = handler.slots.get(uiSlot).getStack();

                if (reqStack.isEmpty()) continue;

                int curCount = curStack.isEmpty() ? 0 : curStack.getCount();
                int actualMissing = reqStack.getCount() - curCount;

                if (actualMissing > 0 && (curStack.isEmpty() || ItemMatcher.isSameItem(curStack, reqStack))) {
                    stillNeedsAction = true;
                    ItemStack needed = reqStack.copy();
                    needed.setCount(actualMissing);

                    int playerSlot = findItemInPlayerInv(client, needed);
                    if (playerSlot != -1) {
                        fillFromPlayerInv(client, syncId, playerSlot, uiSlot, actualMissing);
                        movedAny = true;
                        if (delay > 0) break;
                    }
                }
            }
        }

        if (movedAny) {
            actionWaitTicks = delay;
            consecutiveFailures = 0;
            watchdogTimer = 0;
        } else {
            if (stillNeedsAction) {
                if (client.currentScreen instanceof HandledScreen<?> hs) {
                    RealContainerCache.updateFromScreen(client, hs);
                }
                actionQueue.add(() -> client.player.closeHandledScreen());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> checkAndStartGatheringOrFilling(client));
            } else {
                finishTaskAndReturn(client);
            }
        }
    }

    private void finishTaskAndReturn(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> hs) {
            RealContainerCache.updateFromScreen(client, hs);
        }
        sendFeedback(client, Text.translatable("litematica_container_filler.message.fill_completed").getString(), true);

        actionQueue.add(() -> client.player.closeHandledScreen());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));

        actionQueue.add(() -> {
            borrowedItems.removeIf(item -> {
                for (int i = 0; i < 36; i++) {
                    if (client.player.getInventory().getStack(i).isOf(item)) return false;
                }
                return true;
            });

            stashedItemCounts.entrySet().removeIf(entry -> entry.getValue() <= 0);

            if (!openedShulkerSlots.isEmpty() && (!borrowedItems.isEmpty() || !stashedItemCounts.isEmpty())) {
                pendingShulkers.clear();
                pendingShulkers.addAll(openedShulkerSlots);
                currentPhase = Phase.RETURNING;
            } else {
                reset();
            }
        });
    }

    private void returnBorrowedAndStashedItems(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        boolean movedAny = false;

        for (int i = h.slots.size() - 36; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty() && borrowedItems.contains(s.getItem())) {
                client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                movedAny = true;
            }
        }

        for (int i = 0; i < h.slots.size() - 36; i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty() && stashedItemCounts.containsKey(s.getItem())) {
                int neededToRetrieve = stashedItemCounts.get(s.getItem());
                if (neededToRetrieve <= 0) continue;

                int amountInSlot = s.getCount();
                if (amountInSlot <= neededToRetrieve) {
                    client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                    stashedItemCounts.put(s.getItem(), neededToRetrieve - amountInSlot);
                } else {
                    int emptySlot = -1;
                    for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                        if (h.slots.get(j).getStack().isEmpty()) {
                            emptySlot = j; break;
                        }
                    }
                    if (emptySlot != -1) {
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                        for (int k = 0; k < neededToRetrieve; k++) {
                            client.interactionManager.clickSlot(h.syncId, emptySlot, 1, SlotActionType.PICKUP, client.player);
                        }
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                        stashedItemCounts.put(s.getItem(), 0);
                    } else {
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                        stashedItemCounts.put(s.getItem(), neededToRetrieve - amountInSlot);
                    }
                }
                movedAny = true;
            }
        }

        if (movedAny) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.returning_items").getString(), true);
        }

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            silentlyExtracting = false;
            activeShulkerSlot = -1;
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void waitForUi() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.currentScreenHandler == client.player.playerScreenHandler) {
            uiWaitTimer++;
            if (uiWaitTimer > 20) {
                abortTask(client, "litematica_container_filler.message.container_timeout");
                return;
            }
            actionQueue.addFirst(this::waitForUi);
            yieldTick = true;
        } else {
            uiWaitTimer = 0;
        }
    }

    private void reset() {
        currentTask = null;
        currentMapper = null;
        silentlyExtracting = false;
        yieldTick = false;
        currentPhase = Phase.IDLE;
        actionQueue.clear();
        pendingShulkers.clear();
        actionWaitTicks = 0;
        watchdogTimer = 0;
        uiWaitTimer = 0;
        dataWaitTimer = 0;
        activeShulkerSlot = -1;
        stashShulkerSlot = -1;
        stashItemSlot = -1;
        borrowedItems.clear();
        stashedItemCounts.clear();
        openedShulkerSlots.clear();
        shulkerMisses.clear();
        consecutiveFailures = 0;
    }

    private void sendFeedback(MinecraftClient client, String text, boolean isActionBar) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), isActionBar);
    }

    private boolean hasItemAnywhere(MinecraftClient client, ItemStack target) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(s, target)) return true;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
                if (c != null) {
                    for (ItemStack inner : c.stream().toList()) {
                        if (ItemMatcher.isSameItem(inner, target)) return true;
                    }
                }
            }
        }
        return false;
    }

    private int countItemInPlayerInv(MinecraftClient client, ItemStack target) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(s, target)) count += s.getCount();
        }
        return count;
    }

    private int findItemInPlayerInv(MinecraftClient client, ItemStack target) {
        for (int i = 0; i < 36; i++) if (ItemMatcher.isSameItem(client.player.getInventory().getStack(i), target)) return i;
        return -1;
    }

    private boolean tryPlaceCursorItem(MinecraftClient client, ScreenHandler handler) {
        int empty = findEmptyPlayerSlot(client);
        if (empty != -1) {
            client.interactionManager.clickSlot(handler.syncId, currentMapper.getUiSlotForPlayer(empty), 0, SlotActionType.PICKUP, client.player);
            return true;
        }
        return false;
    }

    private int findEmptyPlayerSlot(MinecraftClient client) {
        for (int i = 9; i < 36; i++) if (client.player.getInventory().getStack(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getStack(i).isEmpty()) return i;
        return -1;
    }

    private void fillFromPlayerInv(MinecraftClient client, int syncId, int playerSlot, int containerSlot, int needed) {
        int uiPlayerSlot = currentMapper.getUiSlotForPlayer(playerSlot);
        ItemStack sourceStack = client.player.getInventory().getStack(playerSlot);
        int countInSlot = sourceStack.getCount();
        int amountToMove = Math.min(needed, countInSlot);

        if (amountToMove == countInSlot) {
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
        } else {
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
            for (int i = 0; i < amountToMove; i++) {
                client.interactionManager.clickSlot(syncId, containerSlot, 1, SlotActionType.PICKUP, client.player);
            }
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
        }
    }

    public BlockPos getCurrentTaskPos() { return currentTask != null ? currentTask.targetPos : null; }
    public boolean isIdle() { return this.currentTask == null && this.actionQueue.isEmpty(); }

    private void simulateSlotClick(HandledScreen<?> screen, Slot slot, int slotId, int button, SlotActionType actionType) {
        try {
            java.lang.reflect.Method targetMethod = null;
            Class<?> currClass = screen.getClass();
            while (currClass != null && targetMethod == null) {
                for (java.lang.reflect.Method m : currClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4 && params[0] == Slot.class && params[1] == int.class && params[2] == int.class && params[3] == SlotActionType.class) {
                        targetMethod = m; break;
                    }
                }
                currClass = currClass.getSuperclass();
            }
            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                targetMethod.invoke(screen, slot, slotId, button, actionType);
            } else {
                MinecraftClient.getInstance().interactionManager.clickSlot(screen.getScreenHandler().syncId, slotId, button, actionType, MinecraftClient.getInstance().player);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}