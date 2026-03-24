package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
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

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        String text = "模式: 仅建筑方块";
        if (FillMaterialCalculator.listMode == 1) text = "模式: 仅容器物品";
        else if (FillMaterialCalculator.listMode == 2) text = "模式: 容器+方块";

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
                } else if (name.contains("MATERIAL_LIST") && (name.contains("LAYER") || name.contains("MODE") || name.contains("TYPE"))) {
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
                            for (java.lang.reflect.Method m : p.getClass().getMethods()) {
                                if (m.getName().equals("getConfigs") && m.getParameterCount() == 0) {
                                    java.util.List<?> configs = (java.util.List<?>) m.invoke(p);
                                    if (configs != null) {
                                        for (Object cfg : configs) {
                                            try {
                                                String sVal = (String) cfg.getClass().getMethod("getStringValue").invoke(cfg);
                                                sb.append(sVal).append("_");
                                            } catch (Exception e1) {
                                                try {
                                                    boolean bVal = (Boolean) cfg.getClass().getMethod("getBooleanValue").invoke(cfg);
                                                    sb.append(bVal).append("_");
                                                } catch (Exception e2) {
                                                    sb.append(cfg.toString()).append("_");
                                                }
                                            }
                                        }
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

            if (FillMaterialCalculator.listMode == 1) {
                FillMaterialCalculator.calculate(this, true);
                List<fi.dy.masa.litematica.materials.MaterialListEntry> containerEntries = FillMaterialCalculator.getCustomMaterialList();

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
                FillMaterialCalculator.calculate(this, true);
                List<fi.dy.masa.litematica.materials.MaterialListEntry> containerEntries = FillMaterialCalculator.getCustomMaterialList();

                if (containerEntries.isEmpty()) {
                    mimic_lastContentHash = currentHash;
                    mimic_lastLayerHash = currentLayerHash;
                    return;
                }

                java.util.List<Object> toAdd = new java.util.ArrayList<>();

                for (fi.dy.masa.litematica.materials.MaterialListEntry cEntry : containerEntries) {
                    ItemStack cStack = getStackFromEntry(cEntry);
                    long cTotal = getCountField(cEntry, 0);
                    long cMissing = getCountField(cEntry, 1);
                    long cAvailable = getCountField(cEntry, 2);
                    long cMismatch = getCountField(cEntry, 3);

                    boolean found = false;
                    for (int i = 0; i < rawMaterials.size(); i++) {
                        Object eObj = rawMaterials.get(i);
                        ItemStack eStack = getStackFromEntry(eObj);

                        if (eStack != null && cStack != null && com.mimicenzymes.litematicafiller.core.ItemMatcher.isSameItem(eStack, cStack)) {
                            long eTotal = getCountField(eObj, 0);
                            long eMissing = getCountField(eObj, 1);
                            long eAvailableOld = getCountField(eObj, 2);
                            long eMismatchOld = getCountField(eObj, 3);

                            fi.dy.masa.litematica.materials.MaterialListEntry merged = new fi.dy.masa.litematica.materials.MaterialListEntry(
                                    eStack, (int)(eTotal + cTotal), (int)(eMissing + cMissing), (int)(eAvailableOld + cAvailable), (int)(eMismatchOld + cMismatch)
                            );

                            rawMaterials.set(i, merged);
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        toAdd.add(cEntry);
                    }
                }

                rawMaterials.addAll(toAdd);

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
                    if (m.getParameterCount() == 0 && (name.equals("recalculate") || name.equals("refresh") || name.equals("update") || name.equals("recreatemateriallist") || name.equals("recalculatetotalupdate"))) {
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