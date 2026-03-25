package com.mimicenzymes.litematicafiller.materials;

import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class FillMaterialCalculator {
    public static boolean isFillMode = false;
    public static int listMode = 0;

    private static class ItemStats {
        int totalAll = 0, missingAll = 0, availableAll = 0, mismatchAll = 0;
        int totalLayer = 0, missingLayer = 0, availableLayer = 0, mismatchLayer = 0;
        ItemStack representative = ItemStack.EMPTY;
    }

    private static class NbtContext {
        final NbtCompound nbt;
        final Object placement;
        NbtContext(NbtCompound nbt, Object placement) {
            this.nbt = nbt;
            this.placement = placement;
        }
    }

    private static final Map<Item, ItemStats> itemStatsCache = new HashMap<>();

    private interface LayerFilter {
        boolean test(int absoluteY, int relativeY);
    }

    private static class MathConfig {
        String mode = "ALL";
        int singleY = 0, minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
        boolean found = false;
    }

    public static void calculate(Object input, boolean silent) {
        itemStatsCache.clear();
        int foundContainersAll = 0, foundContainersLayer = 0;
        int foundItemsAll = 0, foundItemsLayer = 0;
        MinecraftClient client = MinecraftClient.getInstance();

        List<Object> placementsToScan = new ArrayList<>();

        if (input instanceof Collection<?> coll) {
            placementsToScan.addAll(coll);
        } else if (input != null && input.getClass().getSimpleName().endsWith("Placement")) {
            placementsToScan.add(input);
        } else {
            Object gui = null;
            if (client.currentScreen != null && client.currentScreen.getClass().getSimpleName().contains("MaterialList")) {
                gui = client.currentScreen;
            }
            if (gui != null) {
                Class<?> currGuiCls = gui.getClass();
                boolean foundList = false;
                while (currGuiCls != null && currGuiCls != Object.class && !foundList) {
                    for (java.lang.reflect.Field f : currGuiCls.getDeclaredFields()) {
                        if (f.getType().getSimpleName().contains("MaterialList") && !f.getType().getSimpleName().contains("Widget")) {
                            f.setAccessible(true);
                            try {
                                Object mList = f.get(gui);
                                if (mList != null) {
                                    Class<?> mListCls = mList.getClass();
                                    while (mListCls != null && mListCls != Object.class) {
                                        for (java.lang.reflect.Field mf : mListCls.getDeclaredFields()) {
                                            String typeName = mf.getType().getSimpleName();
                                            if (typeName.equals("SchematicPlacement") || typeName.endsWith("Placement") || typeName.equals("SelectionManager")) {
                                                mf.setAccessible(true);
                                                Object p = mf.get(mList);
                                                if (p != null) placementsToScan.add(p);
                                                foundList = true;
                                                break;
                                            }
                                        }
                                        if (foundList) break;
                                        mListCls = mListCls.getSuperclass();
                                    }
                                }
                            } catch (Exception ignored) {}
                            if (foundList) break;
                        }
                    }
                    currGuiCls = currGuiCls.getSuperclass();
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            try {
                Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
                Collection<?> all = (Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
                if (all != null) {
                    for (Object p : all) {
                        boolean enabled = true;
                        try { enabled = (boolean) p.getClass().getMethod("isEnabled").invoke(p); } catch (Exception e) {}
                        if (enabled) placementsToScan.add(p);
                    }
                }
            } catch (Exception ignored) {}
        }

        if (placementsToScan.isEmpty()) return;

        boolean isLayerAbsolute = false;
        try {
            for (java.lang.reflect.Field f : fi.dy.masa.litematica.config.Configs.Generic.class.getFields()) {
                String cleanName = f.getName().toUpperCase().replace("_", "");
                if (cleanName.equals("RENDERLAYERASABSOLUTECOORDS") || cleanName.equals("RENDERLAYERASABSOLUTE")) {
                    Object opt = f.get(null);
                    if (opt != null) isLayerAbsolute = (Boolean) opt.getClass().getMethod("getBooleanValue").invoke(opt);
                    break;
                }
            }
        } catch (Exception ignored) {}

        LayerFilter globalFilter = buildGlobalFilter(isLayerAbsolute);
        Map<Object, LayerFilter> placementFilters = new HashMap<>();
        Map<Object, Integer> placementOriginYMap = new HashMap<>();

        for (Object placement : placementsToScan) {
            LayerFilter pFilter = buildPlacementFilter(placement, isLayerAbsolute);
            if (pFilter != null) {
                placementFilters.put(placement, pFilter);
            }

            BlockPos pOrigin = extractOrigin(placement);
            placementOriginYMap.put(placement, pOrigin != null ? pOrigin.getY() : 0);
        }

        Map<BlockPos, NbtContext> nbtMap = new HashMap<>();
        Set<Object> visitedObj = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Object placement : placementsToScan) {
            List<NbtCompound> nbts = new ArrayList<>();
            extractNbtsFromMemory(placement, nbts, visitedObj, 0);

            BlockPos origin = extractOrigin(placement);
            if (origin == null) origin = BlockPos.ORIGIN;

            for (NbtCompound nbt : nbts) {
                if (nbt.contains("x") && nbt.contains("y") && nbt.contains("z")) {
                    int nx = getIntFromNbt(nbt.get("x"));
                    int ny = getIntFromNbt(nbt.get("y"));
                    int nz = getIntFromNbt(nbt.get("z"));

                    BlockPos directPos = new BlockPos(nx, ny, nz);
                    BlockPos offsetPos = origin.add(nx, ny, nz);
                    double distDirect = directPos.getSquaredDistance(origin);
                    double distOffset = offsetPos.getSquaredDistance(origin);
                    BlockPos worldPos = (distDirect < distOffset) ? directPos : offsetPos;

                    nbtMap.put(worldPos, new NbtContext(nbt, placement));
                }
            }
        }

        Set<BlockPos> globalVisited = new HashSet<>();

        for (Map.Entry<BlockPos, NbtContext> entry : nbtMap.entrySet()) {
            BlockPos pos = entry.getKey();
            NbtContext ctx = entry.getValue();

            if (globalVisited.contains(pos)) continue;

            int nbtY = getIntFromNbt(ctx.nbt.get("y"));
            int originY = placementOriginYMap.getOrDefault(ctx.placement, 0);
            int absoluteY = originY + nbtY;

            boolean inLayer = true;
            LayerFilter pFilter = placementFilters.get(ctx.placement);
            if (pFilter != null) {
                inLayer = pFilter.test(absoluteY, nbtY);
            } else if (globalFilter != null) {
                inLayer = globalFilter.test(absoluteY, nbtY);
            }

            Map<Integer, ItemStack> required = RealContainerCache.parseNbtInventory(ctx.nbt, client.world.getRegistryManager());

            MaterialReplacer.replaceInMap(required);

            BlockPos mainPos = pos;
            BlockState realState = client.world.getBlockState(pos);

            if (realState.hasBlockEntity()) {
                BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, realState);
                if (halves != null) {
                    mainPos = halves[0];
                    globalVisited.add(halves[0]);
                    globalVisited.add(halves[1]);

                    boolean isPrimary = pos.equals(halves[0]);
                    BlockPos otherPos = isPrimary ? halves[1] : halves[0];

                    if (nbtMap.containsKey(otherPos)) {
                        Map<Integer, ItemStack> otherReq = RealContainerCache.parseNbtInventory(nbtMap.get(otherPos).nbt, client.world.getRegistryManager());

                        // 【核心恢复】：同时替换另外半边箱子
                        MaterialReplacer.replaceInMap(otherReq);

                        if (isPrimary) {
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                required.put(e.getKey() + 27, e.getValue());
                            }
                        } else {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                shifted.put(e.getKey(), e.getValue());
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
            if (realItems == null) realItems = new HashMap<>();

            Map<Item, Integer> reqAgg = new HashMap<>();
            Map<Item, Integer> realAgg = new HashMap<>();

            for (ItemStack s : required.values()) {
                reqAgg.merge(s.getItem(), s.getCount(), Integer::sum);
                ItemStats stats = itemStatsCache.computeIfAbsent(s.getItem(), k -> new ItemStats());
                if (stats.representative.isEmpty()) stats.representative = s.copy();
            }

            for (ItemStack s : realItems.values()) {
                realAgg.merge(s.getItem(), s.getCount(), Integer::sum);
            }

            for (Map.Entry<Item, Integer> e : reqAgg.entrySet()) {
                Item item = e.getKey();
                int req = e.getValue();
                int real = realAgg.getOrDefault(item, 0);

                ItemStats stats = itemStatsCache.get(item);
                int matched = Math.min(req, real);

                foundItemsAll += req;
                stats.totalAll += req;
                stats.availableAll += real;
                stats.missingAll += Math.max(0, req - matched);

                if (inLayer) {
                    foundItemsLayer += req;
                    stats.totalLayer += req;
                    stats.availableLayer += real;
                    stats.missingLayer += Math.max(0, req - matched);
                }
            }

            for (Map.Entry<Item, Integer> e : realAgg.entrySet()) {
                Item item = e.getKey();
                int real = e.getValue();
                int req = reqAgg.getOrDefault(item, 0);

                if (real > req) {
                    ItemStats stats = itemStatsCache.computeIfAbsent(item, k -> new ItemStats());
                    if (stats.representative.isEmpty()) stats.representative = new ItemStack(item);

                    stats.mismatchAll += (real - req);
                    if (inLayer) {
                        stats.mismatchLayer += (real - req);
                    }
                }
            }
        }

        if (!silent && client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.translatable("litematica_container_filler.message.parsed_containers", foundContainersAll), false);
        }
    }

    private static boolean testMath(MathConfig config, int targetY) {
        String fMode = config.mode;
        if (fMode.contains("RANGE") || fMode.contains("BOX") || fMode.contains("范围") || fMode.contains("区间")) {
            return (targetY >= config.minY && targetY <= config.maxY);
        } else if (fMode.contains("BELOW") || fMode.contains("DOWN") || fMode.contains("下方") || fMode.contains("以下") || fMode.contains("低于")) {
            return (targetY <= config.singleY);
        } else if (fMode.contains("ABOVE") || fMode.contains("UP") || fMode.contains("上方") || fMode.contains("以上") || fMode.contains("高于")) {
            return (targetY >= config.singleY);
        } else if (fMode.contains("SINGLE") || fMode.contains("LAYER") || fMode.contains("单层") || fMode.contains("层")) {
            return (targetY == config.singleY);
        }
        return true;
    }

    private static boolean isAllMode(String fMode) {
        return fMode.equals("ALL") || fMode.equals("NORMAL") || fMode.equals("NONE") || fMode.equals("全部") || fMode.equals("所有");
    }

    private static String extractButtonText(Object btn) {
        if (btn == null) return "";
        if (btn instanceof String) return (String) btn;
        if (btn.getClass().isEnum()) return ((Enum<?>)btn).name();

        try {
            Object textObj = btn.getClass().getMethod("getMessage").invoke(btn);
            if (textObj != null) return (String) textObj.getClass().getMethod("getString").invoke(textObj);
        } catch (Exception e) {}
        try { return (String) btn.getClass().getMethod("getDisplayString").invoke(btn); } catch (Exception e) {}

        Class<?> c = btn.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(btn);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (isRelevantText(s)) return s;
                    } else if (val != null && val.getClass().getName().contains("Text") && !val.getClass().getName().contains("TextField")) {
                        try {
                            String s = (String) val.getClass().getMethod("getString").invoke(val);
                            if (isRelevantText(s)) return s;
                        } catch (Exception ex) {}
                    }
                } catch (Exception e) {}
            }
            c = c.getSuperclass();
        }
        return "";
    }

    private static boolean isRelevantText(String s) {
        if (s == null) return false;
        String upper = s.toUpperCase();
        return upper.contains("SHOW") || upper.contains("DISPLAY") || upper.contains("TYPE") || upper.contains("显示") || upper.contains("类型") ||
                upper.contains("LAYER") || upper.contains("RENDER") || upper.contains("渲染层") || upper.contains("MATERIAL") || upper.contains("材料") ||
                upper.equals("ALL") || upper.contains("全部") || upper.contains("所有") ||
                upper.contains("HIDE") || upper.contains("AVAILABLE") || upper.contains("MISSING") || upper.contains("隐藏") || upper.contains("可用") || upper.contains("缺失");
    }

    private static void parseWidget(Object widget, boolean[] flags) {
        if (widget == null) return;
        String text = extractButtonText(widget);
        if (text != null && !text.isEmpty()) {
            String upper = text.toUpperCase().replace(" ", "").replace(":", "").replace("：", "");

            if (upper.contains("HIDEAVAILABLE") || upper.contains("隐藏已有") || upper.contains("隐藏可用") || upper.contains("MISSING") || upper.contains("缺失") || upper.contains("AVAILABLE") || upper.contains("可用")) {
                if (upper.contains("ON") || upper.contains("开启") || upper.contains("TRUE") || upper.contains("是") || upper.contains("MISSING") || upper.contains("缺失")) flags[2] = true;
                else if (upper.contains("OFF") || upper.contains("关闭") || upper.contains("FALSE") || upper.contains("否") || upper.contains("AVAILABLE") || upper.contains("可用")) flags[3] = true;
            }

            if (upper.startsWith("SHOW") || upper.startsWith("DISPLAY") || upper.startsWith("显示") ||
                    upper.contains("显示类型") || upper.contains("材料列表") || upper.contains("MATERIALLIST") || upper.contains("RENDERLAYER")) {

                boolean wantsLayer = upper.contains("LAYER") || upper.contains("RENDER") || upper.contains("渲染层") || upper.contains("可见") || upper.contains("VISIBLE");
                boolean wantsAll = upper.contains("全部") || upper.contains("所有") || upper.equals("ALL") || upper.contains("SHOWALL") || upper.contains("DISPLAYALL") || upper.contains("TYPEALL");

                if (wantsLayer) {
                    flags[1] = true;
                } else if (wantsAll) {
                    flags[0] = true;
                }
            }

            if (upper.equals("ALL") || upper.equals("全部")) flags[0] = true;
            if (upper.equals("VISIBLE") || upper.equals("可见") || upper.equals("RENDERLAYER") || upper.equals("渲染层")) flags[1] = true;
        }
    }

    private static void parseConfigField(String cleanName, Object val, MathConfig out) {
        if (cleanName.equals("renderlayersingle") || cleanName.equals("renderlayervalue") || cleanName.equals("singlelayer")) {
            Integer v = extractIntegerSafe(val); if (v != null) { out.singleY = v; out.found = true; }
        } else if (cleanName.equals("renderlayerrangemin") || cleanName.equals("layerrangemin") || cleanName.equals("rangemin")) {
            Integer v = extractIntegerSafe(val); if (v != null) { out.minY = v; out.found = true; }
        } else if (cleanName.equals("renderlayerrangemax") || cleanName.equals("layerrangemax") || cleanName.equals("rangemax")) {
            Integer v = extractIntegerSafe(val); if (v != null) { out.maxY = v; out.found = true; }
        } else if (cleanName.equals("renderlayer") || cleanName.equals("renderlayermode") || cleanName.equals("layermode")) {
            String s = extractStringValueSafe(val).toUpperCase();
            if (!s.isEmpty()) { out.mode = s; out.found = true; }
        }
    }

    private static void extractMathConfig(Object target, MathConfig out) {
        if (target == null) return;

        try {
            java.lang.reflect.Method m = target.getClass().getMethod("getConfigs");
            List<?> configs = (List<?>) m.invoke(target);
            if (configs != null) {
                for (Object cfg : configs) {
                    try {
                        String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
                        parseConfigField(name.toLowerCase().replace("_", ""), cfg, out);
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}

        if (out.found) return;

        Class<?> currCls = target.getClass();
        while (currCls != null && currCls != Object.class) {
            for (java.lang.reflect.Field f : currCls.getDeclaredFields()) {
                f.setAccessible(true);
                try { parseConfigField(f.getName().toLowerCase().replace("_", ""), f.get(target), out); } catch (Exception e) {}
            }
            currCls = currCls.getSuperclass();
        }
    }

    private static LayerFilter buildGlobalFilter(boolean isLayerAbsolute) {
        MathConfig config = new MathConfig();

        try {
            Object rangeObj = null;
            try { rangeObj = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange(); } catch (Exception e) {}
            if (rangeObj != null) extractMathConfig(rangeObj, config);
        } catch (Exception e) {}

        if (!config.found) {
            Class<?>[] clsArr = { fi.dy.masa.litematica.config.Configs.Generic.class, fi.dy.masa.litematica.config.Configs.Visuals.class };
            for (Class<?> c : clsArr) {
                try {
                    for (java.lang.reflect.Field f : c.getFields()) {
                        parseConfigField(f.getName().toLowerCase().replace("_", ""), f.get(null), config);
                    }
                } catch (Exception e) {}
            }
        }

        if (config.found) {
            if (isAllMode(config.mode)) return null;

            return (absoluteY, relativeY) -> testMath(config, absoluteY);
        }
        return null;
    }

    private static LayerFilter buildPlacementFilter(Object placement, boolean isLayerAbsolute) {
        if (placement == null) return null;

        MathConfig config = new MathConfig();

        try {
            Object rangeObj = null;
            for (java.lang.reflect.Method m : placement.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("LayerRange")) {
                    rangeObj = m.invoke(placement);
                    break;
                }
            }
            if (rangeObj != null) extractMathConfig(rangeObj, config);
        } catch (Exception e) {}

        if (!config.found) extractMathConfig(placement, config);

        if (config.found) {
            if (isAllMode(config.mode)) return null;
            return (absoluteY, relativeY) -> testMath(config, isLayerAbsolute ? absoluteY : relativeY);
        }
        return null;
    }

    private static String extractStringValueSafe(Object val) {
        if (val == null) return "ALL";
        if (val instanceof String s) return s;
        if (val instanceof Enum<?> e) return e.name();

        try {
            Object enumVal = val.getClass().getMethod("getOptionListValue").invoke(val);
            if (enumVal != null) {
                if (enumVal instanceof Enum<?>) return ((Enum<?>) enumVal).name();
                try { return (String) enumVal.getClass().getMethod("name").invoke(enumVal); } catch (Exception e) {}
                try { return (String) enumVal.getClass().getMethod("getStringValue").invoke(enumVal); } catch (Exception e) {}
                return enumVal.toString();
            }
        } catch (Exception e) {}

        try { return (String) val.getClass().getMethod("getStringValue").invoke(val); } catch (Exception e) {}
        try {
            Object objVal = val.getClass().getMethod("getValue").invoke(val);
            if (objVal instanceof Enum<?> e) return e.name();
            if (objVal != null) return objVal.toString();
        } catch (Exception e) {}
        return val.toString();
    }

    private static Integer extractIntegerSafe(Object val) {
        if (val == null) return null;
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        try { return (Integer) val.getClass().getMethod("getIntegerValue").invoke(val); } catch (Exception e) {}
        try { return (Integer) val.getClass().getMethod("getIntValue").invoke(val); } catch (Exception e) {}
        try { return Integer.parseInt(val.toString()); } catch (Exception e) {}
        try { return Integer.parseInt(extractStringValueSafe(val)); } catch (Exception e) {}
        return null;
    }

    private static int getIntFromNbt(NbtElement elem) {
        if (elem instanceof net.minecraft.nbt.AbstractNbtNumber num) return num.intValue();
        return 0;
    }

    private static BlockPos extractOrigin(Object placement) {
        if (placement == null) return null;
        try {
            for (java.lang.reflect.Method m : placement.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == BlockPos.class) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("origin") || name.contains("pos")) {
                        return (BlockPos) m.invoke(placement);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void extractNbtsFromMemory(Object obj, List<NbtCompound> results, Set<Object> visited, int depth) {
        if (obj == null || depth > 25 || !visited.add(obj)) return;

        if (obj instanceof NbtCompound c) {
            if (c.contains("Items")) results.add(c);
            for (String key : c.getKeys()) {
                NbtElement el = c.get(key);
                if (el instanceof NbtCompound child) extractNbtsFromMemory(child, results, visited, depth + 1);
                else if (el instanceof NbtList list) {
                    for (int i = 0; i < list.size(); i++) extractNbtsFromMemory(list.get(i), results, visited, depth + 1);
                }
            }
            return;
        }

        if (obj instanceof net.minecraft.block.entity.BlockEntity be) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null) {
                try {
                    NbtCompound c = be.createNbt(client.world.getRegistryManager());
                    if (c != null && c.contains("Items")) results.add(c);
                } catch (Exception ignored) {}
            }
            return;
        }

        if (obj instanceof Map<?, ?> map) {
            for (Object val : map.values()) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }
        if (obj instanceof Iterable<?> iter) {
            for (Object val : iter) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            for (Object val : (Object[]) obj) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }

        String pkg = obj.getClass().getPackage() != null ? obj.getClass().getPackage().getName() : "";
        if (!pkg.startsWith("fi.dy.masa")) return;

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                String fname = f.getName().toLowerCase();
                if (fname.contains("parent") || fname.contains("screen") || fname.contains("gui") || fname.contains("client") || fname.contains("world") || fname.contains("manager")) continue;
                try {
                    f.setAccessible(true);
                    extractNbtsFromMemory(f.get(obj), results, visited, depth + 1);
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static List<MaterialListEntry> getCustomMaterialList() {
        boolean limitToLayer = true;
        boolean hideAvailable = false;

        try {
            for (java.lang.reflect.Field f : fi.dy.masa.litematica.config.Configs.Generic.class.getFields()) {
                String cleanName = f.getName().toUpperCase().replace("_", "");

                if (cleanName.contains("MATERIAL_LIST") && cleanName.contains("HIDE") && cleanName.contains("AVAILABLE")) {
                    Object cb = f.get(null);
                    hideAvailable = (Boolean) cb.getClass().getMethod("getBooleanValue").invoke(cb);
                }

                if (cleanName.equals("MATERIALLISTDISPLAYTYPE") || cleanName.equals("MATERIALLISTLIMITTOLAYER") || cleanName.equals("MATERIALLISTIGNORERENDERLAYER")) {
                    Object opt = f.get(null);
                    if (opt != null) {
                        boolean resolved = false;
                        try {
                            boolean bVal = (Boolean) opt.getClass().getMethod("getBooleanValue").invoke(opt);
                            limitToLayer = cleanName.contains("IGNORE") ? !bVal : bVal;
                            resolved = true;
                        } catch (Exception ignored) {}

                        if (!resolved) {
                            String sVal = extractStringValueSafe(opt).toUpperCase();
                            if (sVal.equals("ALL") || sVal.equals("NONE") || sVal.contains("全部") || sVal.contains("所有")) {
                                limitToLayer = false;
                            } else {
                                limitToLayer = true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        boolean[] flags = new boolean[4];
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen != null) {
            Object gui = client.currentScreen;
            boolean isMaterialListGui = false;
            Class<?> checkCls = gui.getClass();
            while (checkCls != null && checkCls != Object.class) {
                if (checkCls.getSimpleName().contains("MaterialList")) {
                    isMaterialListGui = true;
                    break;
                }
                checkCls = checkCls.getSuperclass();
            }

            if (isMaterialListGui) {
                Class<?> curr = gui.getClass();
                while (curr != null && curr != Object.class) {
                    for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(gui);
                            if (val == null) continue;

                            if (val instanceof List) {
                                for (Object item : (List<?>) val) {
                                    parseWidget(item, flags);
                                }
                            }
                            else if (val.getClass().isArray()) {
                                for (Object item : (Object[]) val) {
                                    parseWidget(item, flags);
                                }
                            }
                            else {
                                parseWidget(val, flags);
                            }
                        } catch (Exception e) {}
                    }
                    curr = curr.getSuperclass();
                }
            }
        }

        if (flags[1]) {
            limitToLayer = true;
        } else if (flags[0]) {
            limitToLayer = false;
        }

        if (flags[2]) hideAvailable = true;
        else if (flags[3]) hideAvailable = false;

        List<MaterialListEntry> list = new ArrayList<>();
        for (Map.Entry<Item, ItemStats> entry : itemStatsCache.entrySet()) {
            ItemStats stats = entry.getValue();

            int total = limitToLayer ? stats.totalLayer : stats.totalAll;
            int missing = limitToLayer ? stats.missingLayer : stats.missingAll;
            int available = limitToLayer ? stats.availableLayer : stats.availableAll;
            int mismatch = limitToLayer ? stats.mismatchLayer : stats.mismatchAll;

            if (total == 0 && missing == 0 && available == 0 && mismatch == 0) continue;

            if (hideAvailable && missing <= 0 && mismatch <= 0) {
                continue;
            }

            ItemStack stack = stats.representative;
            if (stack.isEmpty()) stack = new ItemStack(entry.getKey());

            MaterialListEntry matEntry = new MaterialListEntry(stack, total, missing, available, mismatch);
            list.add(matEntry);
        }
        return list;
    }
}