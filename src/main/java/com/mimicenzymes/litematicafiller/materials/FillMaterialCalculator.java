package com.mimicenzymes.litematicafiller.materials;

import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.mixin.MaterialListPlacementAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class FillMaterialCalculator {
    public static boolean isFillMode = false;
    public static int listMode = 0;

    public static volatile boolean hasMissingData = false;

    private static final Map<SchematicPlacement, List<NbtContext>> PLACEMENT_NBT_CACHE = new IdentityHashMap<>();

    /**
     * Key for aggregating items. For regular items, groups by item type + custom name.
     * For container items (shulker boxes), also includes a hash of the container contents
     * so that shulker boxes with different items are shown separately.
     */
    public static class ItemStackKey {
        public final Item item;
        public final String customName;
        public final int containerHash;

        public ItemStackKey(ItemStack stack) {
            this.item = stack.getItem();
            net.minecraft.text.Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
            this.customName = name != null ? name.getString() : "";
            this.containerHash = computeContainerHash(stack);
        }

        /**
         * Compute a hash of the container contents for shulker boxes.
         * Returns 0 for non-container items.
         */
        private static int computeContainerHash(ItemStack stack) {
            // Only compute container hash for shulker box items
            if (!(stack.getItem() instanceof BlockItem blockItem) ||
                !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                return 0;
            }

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) return 0;

            // Build a content-based hash from all items in the container
            int hash = 0;
            int slot = 0;
            for (ItemStack contained : container.iterateNonEmpty()) {
                hash = 31 * hash + Registries.ITEM.getId(contained.getItem()).hashCode();
                hash = 31 * hash + contained.getCount();
                hash = 31 * hash + slot;
                // Include custom name of contained items too
                net.minecraft.text.Text cName = contained.get(DataComponentTypes.CUSTOM_NAME);
                if (cName != null) hash = 31 * hash + cName.getString().hashCode();
                slot++;
            }
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemStackKey)) return false;
            ItemStackKey that = (ItemStackKey) o;
            return item.equals(that.item) && customName.equals(that.customName) && containerHash == that.containerHash;
        }

        @Override
        public int hashCode() {
            int result = 31 * item.hashCode() + customName.hashCode();
            return 31 * result + containerHash;
        }
    }

    private static class ItemStats {
        int totalAll = 0, missingAll = 0, availableAll = 0, mismatchAll = 0;
        int totalLayer = 0, missingLayer = 0, availableLayer = 0, mismatchLayer = 0;
        ItemStack representative = ItemStack.EMPTY;
    }

    private static class NbtContext {
        final BlockPos worldPos;
        final SchematicPlacement placement;
        final Map<Integer, ItemStack> parsedRequired;

        NbtContext(BlockPos worldPos, SchematicPlacement placement, Map<Integer, ItemStack> parsedRequired) {
            this.worldPos = worldPos;
            this.placement = placement;
            this.parsedRequired = parsedRequired;
        }
    }

    private static final Map<ItemStackKey, ItemStats> itemStatsCache = new HashMap<>();

    public static void calculate(Object input, boolean silent) {
        itemStatsCache.clear();

        if (!silent) {
            PLACEMENT_NBT_CACHE.clear();
        }

        int foundContainersAll = 0, foundContainersLayer = 0;
        boolean waitingForData = false;

        MinecraftClient client = MinecraftClient.getInstance();

        List<SchematicPlacement> placementsToScan = new ArrayList<>();

        if (input instanceof SchematicPlacement sp) {
            placementsToScan.add(sp);
        } else if (input instanceof Collection<?> coll) {
            for (Object obj : coll) {
                if (obj instanceof SchematicPlacement sp) placementsToScan.add(sp);
            }
        } else {
            // Extract MaterialListBase from the input
            MaterialListBase matList = extractMaterialList(input);

            if (matList != null) {
                // Get SchematicPlacement via accessor mixin (no reflection!)
                if (matList instanceof MaterialListPlacement mlp) {
                    SchematicPlacement sp = ((MaterialListPlacementAccessor) mlp).getPlacement();
                    if (sp != null) {
                        placementsToScan.add(sp);
                    }
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager != null) {
                for (SchematicPlacement p : manager.getAllSchematicsPlacements()) {
                    if (p.isEnabled()) placementsToScan.add(p);
                }
            }
        }

        if (placementsToScan.isEmpty()) return;

        Map<BlockPos, NbtContext> nbtMap = new HashMap<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();

        for (SchematicPlacement placement : placementsToScan) {
            List<NbtContext> cachedCtx = PLACEMENT_NBT_CACHE.get(placement);

            if (cachedCtx == null) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;

                int totalTEs = 0;
                for (String regionName : schematic.getAreaPositions().keySet()) {
                    Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
                    if (teMap != null) totalTEs += teMap.size();
                }

                if (totalTEs == 0) {
                    PLACEMENT_NBT_CACHE.put(placement, Collections.emptyList());
                    continue;
                }

                Map<BlockPos, NbtContext> bestMap = new HashMap<>();

                if (schematicWorld != null) {
                    for (Box box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
                        BlockPos p1 = box.getPos1();
                        BlockPos p2 = box.getPos2();
                        int minX = Math.min(p1.getX(), p2.getX());
                        int maxX = Math.max(p1.getX(), p2.getX());
                        int minY = Math.min(p1.getY(), p2.getY());
                        int maxY = Math.max(p1.getY(), p2.getY());
                        int minZ = Math.min(p1.getZ(), p2.getZ());
                        int maxZ = Math.max(p1.getZ(), p2.getZ());

                        for (int x = minX; x <= maxX; x++) {
                            for (int y = minY; y <= maxY; y++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    BlockPos worldPos = new BlockPos(x, y, z);
                                    BlockState state = schematicWorld.getBlockState(worldPos);

                                    if (state != null && state.hasBlockEntity()) {
                                        BlockEntity be = schematicWorld.getBlockEntity(worldPos);
                                        if (be != null) {
                                            NbtCompound nbt = be.createNbt(client.world.getRegistryManager());
                                            if (nbt != null && nbt.contains("Items")) {
                                                Map<Integer, ItemStack> parsedReq = RealContainerCache.parseNbtInventory(nbt, client.world.getRegistryManager());
                                                MaterialReplacer.replaceInMap(parsedReq);

                                                NbtContext existing = bestMap.get(worldPos);
                                                if (existing == null || getItemsCount(nbt) > getItemsCount(existing.parsedRequired)) {
                                                    bestMap.put(worldPos, new NbtContext(worldPos, placement, parsedReq));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                cachedCtx = new ArrayList<>(bestMap.values());
                PLACEMENT_NBT_CACHE.put(placement, cachedCtx);
            }

            for (NbtContext ctx : cachedCtx) {
                nbtMap.put(ctx.worldPos, ctx);
            }
        }

        Set<BlockPos> globalVisited = new java.util.HashSet<>();

        LayerRange layerRange = DataManager.getRenderLayerRange();

        for (Map.Entry<BlockPos, NbtContext> entry : nbtMap.entrySet()) {
            BlockPos pos = entry.getKey();
            NbtContext ctx = entry.getValue();

            if (globalVisited.contains(pos)) continue;

            boolean inLayer = true;
            if (layerRange != null) {
                inLayer = layerRange.isPositionWithinRange(pos);
            }

            Map<Integer, ItemStack> required = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> e : ctx.parsedRequired.entrySet()) {
                required.put(e.getKey(), e.getValue().copy());
            }

            BlockPos mainPos = pos;
            BlockState realState = client.world.getBlockState(pos);
            BlockState schState = schematicWorld != null ? schematicWorld.getBlockState(pos) : null;

            boolean useSch = !realState.hasBlockEntity() && schState != null && schState.hasBlockEntity();
            BlockState stateForHalves = useSch ? schState : realState;
            net.minecraft.world.World worldForHalves = useSch ? (net.minecraft.world.World) (Object) schematicWorld : client.world;

            BlockPos[] halves = null;
            if (stateForHalves.hasBlockEntity()) {
                halves = LitematicaContainerReader.getDoubleContainerHalves(worldForHalves, pos, stateForHalves);
                if (halves != null) {
                    mainPos = halves[0];
                    globalVisited.add(halves[0]);
                    globalVisited.add(halves[1]);

                    boolean isPrimary = pos.equals(halves[0]);
                    BlockPos otherPos = isPrimary ? halves[1] : halves[0];

                    if (nbtMap.containsKey(otherPos)) {
                        Map<Integer, ItemStack> otherReq = nbtMap.get(otherPos).parsedRequired;

                        if (isPrimary) {
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                required.put(e.getKey() + 27, e.getValue().copy());
                            }
                        } else {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                shifted.put(e.getKey(), e.getValue().copy());
                            }
                            required = shifted;
                        }
                    } else {
                        if (!isPrimary) {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            required = shifted;
                        }
                    }
                } else {
                    globalVisited.add(pos);
                }
            } else {
                globalVisited.add(pos);
            }

            if (required.isEmpty()) continue;

            foundContainersAll++;
            if (inLayer) foundContainersLayer++;

            Map<Integer, ItemStack> realItems = RealContainerCache.getCachedItems(mainPos);

            if (realItems == null && halves != null) {
                BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                realItems = RealContainerCache.getCachedItems(otherHalf);
                if (realItems != null) mainPos = otherHalf;
            }

            if (realItems == null) {
                if (client.world != null) {
                    waitingForData = true;
                    RealContainerCache.requestContainerData(mainPos);

                    if (halves != null) {
                        BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                        RealContainerCache.requestContainerData(otherHalf);
                    }
                }
                realItems = new HashMap<>();
            }

            Map<ItemStackKey, Integer> reqAgg = new HashMap<>();
            Map<ItemStackKey, Integer> realAgg = new HashMap<>();

            for (ItemStack s : required.values()) {
                ItemStackKey key = new ItemStackKey(s);
                reqAgg.merge(key, s.getCount(), Integer::sum);
                ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                if (stats.representative.isEmpty()) stats.representative = s.copy();
            }

            for (ItemStack s : realItems.values()) {
                ItemStackKey key = new ItemStackKey(s);
                realAgg.merge(key, s.getCount(), Integer::sum);
            }

            for (Map.Entry<ItemStackKey, Integer> e : reqAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int req = e.getValue();
                int real = realAgg.getOrDefault(key, 0);

                ItemStats stats = itemStatsCache.get(key);

                int matched = Math.min(req, real);

                stats.totalAll += req;
                stats.availableAll += real;
                stats.missingAll += Math.max(0, req - matched);

                if (inLayer) {
                    stats.totalLayer += req;
                    stats.availableLayer += real;
                    stats.missingLayer += Math.max(0, req - matched);
                }
            }

            for (Map.Entry<ItemStackKey, Integer> e : realAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int real = e.getValue();
                int req = reqAgg.getOrDefault(key, 0);

                if (real > req) {
                    ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                    if (stats.representative.isEmpty()) stats.representative = new ItemStack(key.item);

                    stats.mismatchAll += (real - req);
                    if (inLayer) {
                        stats.mismatchLayer += (real - req);
                    }
                }
            }
        }

        hasMissingData = waitingForData;

        if (!silent && client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.translatable("litematica_container_filler.message.parsed_containers", foundContainersAll), false);
        }
    }

    /**
     * Extract MaterialListBase from various input types using direct API calls.
     */
    private static MaterialListBase extractMaterialList(Object input) {
        if (input instanceof MaterialListBase mlb) {
            return mlb;
        }
        // GuiMaterialList has a public getMaterialList() method
        if (input instanceof GuiMaterialList gui) {
            return gui.getMaterialList();
        }
        // Try if input's class has getMaterialList() (e.g. mixin-enhanced class)
        try {
            if (input != null && input.getClass().getSimpleName().contains("GuiMaterialList")) {
                java.lang.reflect.Method m = input.getClass().getMethod("getMaterialList");
                Object result = m.invoke(input);
                if (result instanceof MaterialListBase mlb) return mlb;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int getItemsCount(NbtCompound nbt) {
        if (nbt != null && nbt.contains("Items")) {
            NbtElement el = nbt.get("Items");
            if (el instanceof NbtList list) {
                return list.size();
            }
        }
        return 0;
    }

    private static int getItemsCount(Map<Integer, ItemStack> parsedMap) {
        return parsedMap != null ? parsedMap.size() : 0;
    }

    /**
     * Check if an item is in the material list's ignored set.
     * Uses direct API — MaterialListBase.ignored is checked via the filtered list mechanism.
     * We don't need to check it manually since setMaterialListEntries → refreshPreFilteredList
     * already filters out ignored entries.
     */
    private static boolean isItemIgnored(MaterialListBase materialList, Item item) {
        // MaterialListBase handles ignored items internally via refreshPreFilteredList()
        // No need to check here — the filtering happens automatically when we inject
        return false;
    }

    public static List<MaterialListEntry> getCustomMaterialList(Object materialListObj) {
        boolean limitToLayer = true;

        // Use the public IMaterialList interface to get the list type
        if (materialListObj instanceof IMaterialList iMatList) {
            BlockInfoListType type = iMatList.getMaterialListType();
            if (type != null) {
                limitToLayer = type.name().contains("LAYER");
            }
        }

        List<MaterialListEntry> list = new ArrayList<>();

        for (Map.Entry<ItemStackKey, ItemStats> entry : itemStatsCache.entrySet()) {
            ItemStats stats = entry.getValue();

            int total = limitToLayer ? stats.totalLayer : stats.totalAll;
            int missing = limitToLayer ? stats.missingLayer : stats.missingAll;
            int available = limitToLayer ? stats.availableLayer : stats.availableAll;
            int mismatch = limitToLayer ? stats.mismatchLayer : stats.mismatchAll;

            if (total == 0 && missing == 0 && available == 0 && mismatch == 0) continue;

            ItemStack stack = stats.representative;
            if (stack.isEmpty()) stack = new ItemStack(entry.getKey().item);

            // mismatch is set to 0 because our "mismatch" (extra items in container) is semantically
            // different from Litematica's "mismatch" (wrong block type, subset of missing).
            // Litematica's progress bar formula: missing = countMissing - countMismatched
            // If we pass our independent mismatch value, it produces negative percentages.
            MaterialListEntry matEntry = new MaterialListEntry(stack, total, missing, available, 0);
            list.add(matEntry);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !list.isEmpty()) {
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(list, client.player);
        }

        return list;
    }

    public static List<MaterialListEntry> mergeLists(List<MaterialListEntry> vanillaList, List<MaterialListEntry> containerList) {
        Map<ItemStackKey, MaterialListEntry> mergedMap = new java.util.LinkedHashMap<>();

        for (MaterialListEntry v : vanillaList) {
            mergedMap.put(new ItemStackKey(v.getStack()), v);
        }

        for (MaterialListEntry c : containerList) {
            ItemStackKey key = new ItemStackKey(c.getStack());
            MaterialListEntry v = mergedMap.get(key);

            if (v != null) {
                MaterialListEntry combined = new MaterialListEntry(
                        c.getStack(),
                        v.getCountTotal() + c.getCountTotal(),
                        v.getCountMissing() + c.getCountMissing(),
                        v.getCountAvailable(),
                        v.getCountMismatched() + c.getCountMismatched()
                );
                mergedMap.put(key, combined);
            } else {
                mergedMap.put(key, c);
            }
        }

        return new ArrayList<>(mergedMap.values());
    }
}