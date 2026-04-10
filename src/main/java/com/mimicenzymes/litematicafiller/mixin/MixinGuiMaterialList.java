package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class MixinGuiMaterialList extends GuiBase {

    @Unique
    private long mimic_lastContentHash = -1;
    @Unique
    private String mimic_lastLayerHash = "";
    @Unique
    private boolean mimic_isMonitorRunning = false;
    @Unique
    private boolean mimic_needsCalculation = true;
    @Unique
    private final java.util.Map<String, long[]> mimic_injections = new java.util.HashMap<>();
    @Unique
    private final java.util.Set<String> mimic_appendedKeys = new java.util.HashSet<>();

    @Unique
    private String mimic_getItemKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
        net.minecraft.text.Text name = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
        return id + "|" + (name != null ? name.getString() : "");
    }

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        mimic_needsCalculation = true;

        String text = StringUtils.translate("litematica_container_filler.gui.button.mode_blocks_only");
        if (FillMaterialCalculator.listMode == 1) text = StringUtils.translate("litematica_container_filler.gui.button.mode_containers_only");
        else if (FillMaterialCalculator.listMode == 2) text = StringUtils.translate("litematica_container_filler.gui.button.mode_both");

        int maxX = 10;
        int targetY = 26;

        try {
            java.util.List<?> buttons = null;
            for (java.lang.reflect.Field f : GuiBase.class.getDeclaredFields()) {
                if (f.getType() == java.util.List.class && f.getName().toLowerCase().contains("button")) {
                    f.setAccessible(true);
                    buttons = (java.util.List<?>) f.get(this);
                    break;
                }
            }
            if (buttons != null) {
                for (Object btnObj : buttons) {
                    int btnY = (int) btnObj.getClass().getMethod("getY").invoke(btnObj);
                    int btnX = (int) btnObj.getClass().getMethod("getX").invoke(btnObj);
                    int btnW = (int) btnObj.getClass().getMethod("getWidth").invoke(btnObj);
                    if (btnY < 50) {
                        int rightEdge = btnX + btnW;
                        if (rightEdge > maxX) {
                            maxX = rightEdge;
                            targetY = btnY;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        ButtonGeneric toggleBtn = new ButtonGeneric(maxX + 4, targetY, 120, 20, text);

        this.addButton(toggleBtn, (button, mouseButton) -> {
            FillMaterialCalculator.listMode = (FillMaterialCalculator.listMode + 1) % 3;
            FillMaterialCalculator.isFillMode = (FillMaterialCalculator.listMode != 0);

            mimic_lastContentHash = -1;
            mimic_lastLayerHash = "";
            mimic_needsCalculation = true;

            triggerNativeRecalculate();
            this.initGui();
        });

        if (!mimic_isMonitorRunning) {
            mimic_isMonitorRunning = true;
            Thread monitor = new Thread(() -> {
                while (MinecraftClient.getInstance().currentScreen == this) {
                    try {
                        Thread.sleep(50);
                        if (FillMaterialCalculator.listMode != 0) {
                            MinecraftClient.getInstance().execute(() -> {
                                if (MinecraftClient.getInstance().currentScreen == this) {
                                    mimic_enforceMergedList();
                                }
                            });
                        }
                    } catch (Exception e) {}
                }
                mimic_isMonitorRunning = false;
            });
            monitor.setDaemon(true);
            monitor.setName("LitematicaFiller-MergeWatchdog");
            monitor.start();
        }
    }

    @Unique
    private long calculateContentHash(java.util.List<Object> list) {
        long hash = 0;
        for (Object obj : list) {
            ItemStack stack = getStackFromEntry(obj);
            if (stack != null && !stack.isEmpty()) {
                long itemHash = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().hashCode();
                long total = getCountField(obj, 0);
                long missing = getCountField(obj, 1);
                hash += (itemHash * 31L + total * 17L + missing);
            }
        }
        return hash;
    }

    @Unique
    private boolean mimic_isItemIgnored(Object materialListObj, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.item.Item item = stack.getItem();
        String fullId = net.minecraft.registry.Registries.ITEM.getId(item).toString();
        String path = net.minecraft.registry.Registries.ITEM.getId(item).getPath();

        try {
            if (materialListObj != null) {
                for (java.lang.reflect.Method m : materialListObj.getClass().getMethods()) {
                    if (m.getReturnType() == boolean.class && m.getName().toLowerCase().contains("ignore")) {
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 1) {
                            Class<?> pType = pTypes[0];
                            try {
                                if (pType.isAssignableFrom(ItemStack.class)) {
                                    return (Boolean) m.invoke(materialListObj, stack);
                                } else if (pType.isAssignableFrom(net.minecraft.item.Item.class)) {
                                    return (Boolean) m.invoke(materialListObj, item);
                                } else {
                                    Object paramObj = null;
                                    try { paramObj = pType.getConstructor(ItemStack.class, boolean.class).newInstance(stack, true); } catch (Exception e1) {
                                        try { paramObj = pType.getConstructor(ItemStack.class).newInstance(stack); } catch (Exception e2) {
                                            try { paramObj = pType.getConstructor(net.minecraft.item.Item.class).newInstance(item); } catch (Exception e3) {}
                                        }
                                    }
                                    if (paramObj != null) {
                                        return (Boolean) m.invoke(materialListObj, paramObj);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                Class<?> curr = materialListObj.getClass();
                while (curr != null && curr != Object.class) {
                    for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                        if (java.util.Collection.class.isAssignableFrom(f.getType()) && f.getName().toLowerCase().contains("ignore")) {
                            f.setAccessible(true);
                            java.util.Collection<?> coll = (java.util.Collection<?>) f.get(materialListObj);
                            if (coll != null) {
                                for (Object ignoredObj : coll) {
                                    if (ignoredObj == null) continue;

                                    ItemStack s = getStackFromEntry(ignoredObj);
                                    if (s != null && s.getItem() == item) return true;

                                    try {
                                        for (java.lang.reflect.Method m : ignoredObj.getClass().getMethods()) {
                                            if (m.getParameterCount() == 0 && m.getReturnType() == net.minecraft.item.Item.class) {
                                                if (m.invoke(ignoredObj) == item) return true;
                                            } else if (m.getParameterCount() == 0 && m.getReturnType() == ItemStack.class) {
                                                ItemStack is = (ItemStack) m.invoke(ignoredObj);
                                                if (is != null && is.getItem() == item) return true;
                                            }
                                        }
                                    } catch (Exception ignored) {}

                                    if (ignoredObj.toString().contains(path)) return true;
                                }
                            }
                        }
                    }
                    curr = curr.getSuperclass();
                }
            }

            for (java.lang.reflect.Field f : fi.dy.masa.litematica.config.Configs.Generic.class.getFields()) {
                String fName = f.getName().toUpperCase();
                if (fName.contains("IGNORE") && (fName.contains("TYPE") || fName.contains("ITEM") || fName.contains("MAT"))) {
                    Object opt = f.get(null);
                    if (opt != null) {
                        try {
                            java.util.List<?> strings = (java.util.List<?>) opt.getClass().getMethod("getStrings").invoke(opt);
                            if (strings != null) {
                                for (Object o : strings) {
                                    String s = o.toString();
                                    if (s.equalsIgnoreCase(fullId) || s.equalsIgnoreCase(path)) return true;
                                }
                            }
                        } catch (Exception e1) {
                            try {
                                String val = (String) opt.getClass().getMethod("getStringValue").invoke(opt);
                                if (val != null) {
                                    for (String s : val.replace("[", "").replace("]", "").split(",")) {
                                        if (s.trim().equalsIgnoreCase(fullId) || s.trim().equalsIgnoreCase(path)) return true;
                                    }
                                }
                            } catch (Exception e2) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Unique
    private String getRenderLayerHash() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(com.mimicenzymes.litematicafiller.config.Configs.SYNC_LITE_LAYER.getBooleanValue()).append("_");

            try {
                java.util.List<?> buttons = null;
                for (java.lang.reflect.Field f : fi.dy.masa.malilib.gui.GuiBase.class.getDeclaredFields()) {
                    if (f.getType() == java.util.List.class && f.getName().toLowerCase().contains("button")) {
                        f.setAccessible(true);
                        buttons = (java.util.List<?>) f.get(this);
                        break;
                    }
                }
                if (buttons != null) {
                    for (Object btn : buttons) {
                        String text = "";
                        try { text = (String) btn.getClass().getMethod("getDisplayString").invoke(btn); } catch (Exception e1) {
                            try { text = btn.getClass().getMethod("getMessage").invoke(btn).toString(); } catch (Exception e2) {}
                        }
                        if (text != null && !text.isEmpty()) {
                            sb.append(text).append("_");
                        }
                    }
                }
            } catch (Exception ignored) {}

            for (java.lang.reflect.Field f : fi.dy.masa.litematica.config.Configs.Generic.class.getFields()) {
                String name = f.getName().toUpperCase();
                if (name.equals("MATERIAL_LIST_LIMIT_TO_LAYER") || name.equals("MATERIAL_LIST_IGNORE_LAYER")) {
                    Object opt = f.get(null);
                    if (opt != null) {
                        try {
                            boolean bVal = (Boolean) opt.getClass().getMethod("getBooleanValue").invoke(opt);
                            sb.append(bVal).append("_");
                        } catch (Exception ignored) {}
                    }
                }
                else if (name.contains("IGNORE") && (name.contains("TYPE") || name.contains("ITEM") || name.contains("MAT"))) {
                    Object opt = f.get(null);
                    if (opt != null) {
                        try {
                            java.util.List<?> list = (java.util.List<?>) opt.getClass().getMethod("getStrings").invoke(opt);
                            if (list != null) {
                                for (Object o : list) sb.append(o.toString()).append(",");
                                sb.append("_");
                            }
                        } catch (Exception e1) {
                            try {
                                String sVal = (String) opt.getClass().getMethod("getStringValue").invoke(opt);
                                sb.append(sVal).append("_");
                            } catch (Exception e2) {}
                        }
                    }
                }
                else if (name.contains("MATERIAL_LIST") && (name.contains("LAYER") || name.contains("MODE") || name.contains("TYPE"))) {
                    Object opt = f.get(null);
                    if (opt != null) {
                        try {
                            String sVal = (String) opt.getClass().getMethod("getStringValue").invoke(opt);
                            sb.append(sVal).append("_");
                        } catch (Exception ignored) {}
                    }
                }
            }

            Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            if (manager != null) {
                java.util.Collection<?> all = (java.util.Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
                if (all != null) {
                    for (Object p : all) {
                        try {
                            Object layerRange = p.getClass().getMethod("getLayerRange").invoke(p);
                            if (layerRange != null) {
                                for (java.lang.reflect.Field f : layerRange.getClass().getDeclaredFields()) {
                                    if (f.getType() == int.class) {
                                        f.setAccessible(true);
                                        sb.append(f.getInt(layerRange)).append("_");
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            Object range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
            if (range != null) {
                for (java.lang.reflect.Field f : range.getClass().getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        sb.append(f.getInt(range)).append("_");
                    }
                }
            }
        } catch (Exception e) {}
        return sb.toString();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void mimic_enforceMergedList() {
        try {
            Object materialListObj = null;
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().contains("MaterialList") && !f.getType().getSimpleName().contains("Widget")) {
                    f.setAccessible(true);
                    materialListObj = f.get(this);
                    break;
                }
            }
            if (materialListObj == null) return;

            final Object finalMaterialListObj = materialListObj;

            java.util.List<Object> rawMaterials = null;
            Class<?> curr = materialListObj.getClass();
            while (curr != null && rawMaterials == null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (java.util.List.class.isAssignableFrom(f.getType())) {
                        String fName = f.getName().toLowerCase();
                        if (fName.contains("material") || fName.contains("list") || fName.equals("m_materials")) {
                            f.setAccessible(true);
                            java.util.List<?> list = (java.util.List<?>) f.get(materialListObj);
                            if (list != null) {
                                if (list.isEmpty() || (!list.isEmpty() && list.get(0).getClass().getSimpleName().contains("MaterialListEntry"))) {
                                    rawMaterials = (java.util.List<Object>) list;
                                    break;
                                }
                            }
                        }
                    }
                }
                curr = curr.getSuperclass();
            }

            if (rawMaterials == null) return;

            long currentHash = calculateContentHash(rawMaterials);
            String currentLayerHash = getRenderLayerHash();
            if (currentHash == mimic_lastContentHash && currentLayerHash.equals(mimic_lastLayerHash)) return;
            for (int i = rawMaterials.size() - 1; i >= 0; i--) {
                Object eObj = rawMaterials.get(i);
                ItemStack stack = getStackFromEntry(eObj);
                String key = mimic_getItemKey(stack);

                if (mimic_appendedKeys.contains(key)) {
                    rawMaterials.remove(i);
                } else if (mimic_injections.containsKey(key)) {
                    long[] inj = mimic_injections.get(key);
                    long bTotal = getCountField(eObj, 0) - inj[0];
                    long bMissing = getCountField(eObj, 1) - inj[1];
                    long bAvailable = getCountField(eObj, 2) - inj[2];
                    long bMismatch = getCountField(eObj, 3) - inj[3];

                    fi.dy.masa.litematica.materials.MaterialListEntry restored = new fi.dy.masa.litematica.materials.MaterialListEntry(
                            stack, (int)bTotal, (int)Math.max(0, bMissing), (int)bAvailable, (int)bMismatch
                    );
                    rawMaterials.set(i, restored);
                }
            }
            mimic_appendedKeys.clear();
            mimic_injections.clear();
            if (!currentLayerHash.equals(mimic_lastLayerHash)) {
                mimic_lastLayerHash = currentLayerHash;
                mimic_needsCalculation = true;
                triggerNativeRecalculate();
                return;
            }

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true);
                mimic_needsCalculation = false;
            }

            if (FillMaterialCalculator.listMode == 1) {
                List<fi.dy.masa.litematica.materials.MaterialListEntry> containerEntries = FillMaterialCalculator.getCustomMaterialList(materialListObj);

                if (containerEntries != null) {
                    containerEntries.removeIf(entry -> mimic_isItemIgnored(finalMaterialListObj, getStackFromEntry(entry)));
                }

                rawMaterials.clear();
                if (containerEntries != null && !containerEntries.isEmpty()) {
                    rawMaterials.addAll(containerEntries);
                }

                mimic_lastContentHash = calculateContentHash(rawMaterials);
                mimic_lastLayerHash = currentLayerHash;

                triggerGuiRebuild();
                return;
            }

            if (FillMaterialCalculator.listMode == 2) {
                List<fi.dy.masa.litematica.materials.MaterialListEntry> containerEntries = FillMaterialCalculator.getCustomMaterialList(materialListObj);

                if (containerEntries != null) {
                    containerEntries.removeIf(entry -> mimic_isItemIgnored(finalMaterialListObj, getStackFromEntry(entry)));
                }

                if (containerEntries == null || containerEntries.isEmpty()) {
                    mimic_lastContentHash = calculateContentHash(rawMaterials);
                    mimic_lastLayerHash = currentLayerHash;
                    return;
                }

                java.util.Set<String> matchedKeys = new java.util.HashSet<>();
                for (int i = 0; i < rawMaterials.size(); i++) {
                    Object eObj = rawMaterials.get(i);
                    ItemStack eStack = getStackFromEntry(eObj);
                    if (eStack == null) continue;
                    String eKey = mimic_getItemKey(eStack);

                    long bTotal = getCountField(eObj, 0);
                    long bMissing = getCountField(eObj, 1);
                    long bAvailable = getCountField(eObj, 2);
                    long bMismatch = getCountField(eObj, 3);

                    for (fi.dy.masa.litematica.materials.MaterialListEntry cEntry : containerEntries) {
                        ItemStack cStack = getStackFromEntry(cEntry);
                        String cKey = mimic_getItemKey(cStack);

                        if (eKey.equals(cKey) && !matchedKeys.contains(cKey)) {
                            matchedKeys.add(cKey);

                            long cTotal = getCountField(cEntry, 0);
                            long cMissing = getCountField(cEntry, 1);
                            long cAvailable = getCountField(cEntry, 2);
                            long cMismatch = getCountField(cEntry, 3);

                            fi.dy.masa.litematica.materials.MaterialListEntry merged = new fi.dy.masa.litematica.materials.MaterialListEntry(
                                    eStack,
                                    (int)(bTotal + cTotal),
                                    (int)Math.max(0, bMissing + cMissing),
                                    (int)(bAvailable + cAvailable),
                                    (int)(bMismatch + cMismatch)
                            );
                            rawMaterials.set(i, merged);

                            mimic_injections.put(eKey, new long[]{cTotal, cMissing, cAvailable, cMismatch});
                            break;
                        }
                    }
                }

                for (fi.dy.masa.litematica.materials.MaterialListEntry cEntry : containerEntries) {
                    ItemStack cStack = getStackFromEntry(cEntry);
                    String cKey = mimic_getItemKey(cStack);
                    if (!matchedKeys.contains(cKey)) {
                        rawMaterials.add(cEntry);
                        mimic_appendedKeys.add(cKey);
                    }
                }

                mimic_lastContentHash = calculateContentHash(rawMaterials);
                mimic_lastLayerHash = currentLayerHash;

                triggerGuiRebuild();
            }

        } catch (Exception e) {}
    }

    @Unique
    private void triggerGuiRebuild() {
        try {
            Object widget = null;
            Class<?> curr = this.getClass();
            while (curr != null && widget == null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getName().equals("m_widget") || f.getName().equals("widget")) {
                        f.setAccessible(true);
                        widget = f.get(this);
                        break;
                    }
                }
                curr = curr.getSuperclass();
            }
            if (widget != null) {
                for (java.lang.reflect.Method m : widget.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && void.class.equals(m.getReturnType())) {
                        String name = m.getName().toLowerCase();
                        if (name.equals("refreshentries") || name.equals("recreatelistwidget")) {
                            m.invoke(widget);
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private void triggerNativeRecalculate() {
        try {
            Object materialListObj = null;
            Class<?> curr = this.getClass();
            while (curr != null && materialListObj == null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("MaterialList") && !f.getType().getSimpleName().contains("Widget")) {
                        f.setAccessible(true);
                        materialListObj = f.get(this);
                        break;
                    }
                }
                curr = curr.getSuperclass();
            }
            if (materialListObj != null) {
                for (java.lang.reflect.Method m : materialListObj.getClass().getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (m.getParameterCount() == 0 && (name.equals("recreatemateriallist") || name.equals("recalculatetotalupdate") || name.equals("clearandupdate"))) {
                        m.invoke(materialListObj);
                        return;
                    }
                }
                for (java.lang.reflect.Method m : materialListObj.getClass().getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (m.getParameterCount() == 0 && (name.equals("recalculate") || name.equals("refresh") || name.equals("update"))) {
                        m.invoke(materialListObj);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private ItemStack getStackFromEntry(Object entry) {
        try {
            for (java.lang.reflect.Method m : entry.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == ItemStack.class) {
                    return (ItemStack) m.invoke(entry);
                }
            }
            for (java.lang.reflect.Field f : entry.getClass().getDeclaredFields()) {
                if (f.getType() == ItemStack.class) {
                    f.setAccessible(true);
                    return (ItemStack) f.get(entry);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private long getCountField(Object entry, int index) {
        try {
            int currentIdx = 0;
            for (java.lang.reflect.Field f : entry.getClass().getDeclaredFields()) {
                if (f.getType() == int.class || f.getType() == long.class) {
                    if (currentIdx == index) {
                        f.setAccessible(true);
                        return f.getType() == int.class ? f.getInt(entry) : f.getLong(entry);
                    }
                    currentIdx++;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}