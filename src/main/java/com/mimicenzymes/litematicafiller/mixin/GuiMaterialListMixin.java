package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class GuiMaterialListMixin extends GuiBase {

    @Unique
    private boolean mimic_isMonitorRunning = false;

    @Unique
    private boolean mimic_needsCalculation = true;

    // 【核心重构】：支持多个列表实例同步缓存（同时处理 GUI 局部列表 + HUD 全局列表）
    @Unique
    private final Map<Object, List<MaterialListEntry>> mimic_cachedVanillaLists = new IdentityHashMap<>();

    @Unique
    private final Map<Object, List<MaterialListEntry>> mimic_lastInjectedLists = new IdentityHashMap<>();

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

            mimic_needsCalculation = true;
            mimic_lastInjectedLists.clear();
            this.initGui();
        });

        if (!mimic_isMonitorRunning) {
            mimic_isMonitorRunning = true;
            Thread monitor = new Thread(() -> {
                int tickCount = 0;
                while (MinecraftClient.getInstance().currentScreen == this) {
                    try {
                        Thread.sleep(50);
                        tickCount++;

                        if (FillMaterialCalculator.listMode != 0) {
                            final boolean forceRefresh = (tickCount % 10 == 0) && FillMaterialCalculator.hasMissingData;

                            MinecraftClient.getInstance().execute(() -> {
                                if (MinecraftClient.getInstance().currentScreen == this) {
                                    if (forceRefresh) mimic_needsCalculation = true;
                                    mimic_injectUsingOfficialApi();
                                }
                            });
                        }
                    } catch (Exception e) {}
                }
                mimic_isMonitorRunning = false;
                mimic_cachedVanillaLists.clear();
                mimic_lastInjectedLists.clear();
            });
            monitor.setDaemon(true);
            monitor.setName("LitematicaFiller-InjectionWatchdog");
            monitor.start();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private List<MaterialListEntry> mimic_extractBaseList(Object materialListObj) {
        Class<?> curr = materialListObj.getClass();
        while (curr != null) {
            for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                if (f.getName().equals("materialListAll") || f.getName().equals("m_materials")) {
                    f.setAccessible(true);
                    try {
                        Object listObj = f.get(materialListObj);
                        if (listObj instanceof List) {
                            return (List<MaterialListEntry>) listObj;
                        }
                    } catch (Exception ignored) {}
                    return null;
                }
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    @Unique
    private void mimic_injectUsingOfficialApi() {
        try {
            List<Object> targetListObjs = new ArrayList<>();

            // 1. 抓取 GUI 正在显示的局部列表对象
            Object guiListObj = null;
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().contains("MaterialList") && !f.getType().getSimpleName().contains("Widget")) {
                    f.setAccessible(true);
                    guiListObj = f.get(this);
                    break;
                }
            }
            if (guiListObj instanceof IMaterialList) {
                targetListObjs.add(guiListObj);
            }

            // 【神级修复】：2. 抓取 HUD 悬浮窗正在使用的全局数据源！一并进行注入！
            Object globalListObj = fi.dy.masa.litematica.data.DataManager.getMaterialList();
            if (globalListObj instanceof IMaterialList && !targetListObjs.contains(globalListObj)) {
                targetListObjs.add(globalListObj);
            }

            if (targetListObjs.isEmpty()) return;

            // 为所有的列表（GUI 和 HUD 的）更新原版材料基底缓存
            for (Object materialListObj : targetListObjs) {
                List<MaterialListEntry> currentBaseList = mimic_extractBaseList(materialListObj);
                if (currentBaseList == null) continue;

                if (currentBaseList != mimic_lastInjectedLists.get(materialListObj)) {
                    mimic_cachedVanillaLists.put(materialListObj, new ArrayList<>(currentBaseList));
                    mimic_needsCalculation = true;
                }
            }

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return;
            }

            int scroll = mimic_getScrollPosition();

            // 为 GUI 和 HUD 分别同时注入带容器的混合材料
            for (Object materialListObj : targetListObjs) {
                IMaterialList iMatList = (IMaterialList) materialListObj;
                List<MaterialListEntry> cachedVanilla = mimic_cachedVanillaLists.get(materialListObj);

                if (cachedVanilla == null) continue;

                List<MaterialListEntry> targetList;
                if (FillMaterialCalculator.listMode == 1) {
                    targetList = FillMaterialCalculator.getCustomMaterialList(materialListObj);
                } else if (FillMaterialCalculator.listMode == 2) {
                    targetList = FillMaterialCalculator.mergeLists(cachedVanilla, FillMaterialCalculator.getCustomMaterialList(materialListObj));
                } else {
                    targetList = new ArrayList<>(cachedVanilla);
                }

                // 将计算好的容器数据直接赋值给官方列表的基底
                iMatList.setMaterialListEntries(targetList);

                // 保存新的不可变列表基底防止套娃
                List<MaterialListEntry> newBaseList = mimic_extractBaseList(materialListObj);
                mimic_lastInjectedLists.put(materialListObj, newBaseList != null ? newBaseList : targetList);
            }

            mimic_setScrollPosition(scroll);

        } catch (Exception e) {}
    }

    @Unique
    private int mimic_getScrollPosition() {
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
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                return (int) scrollbar.getClass().getMethod("getValue").invoke(scrollbar);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @Unique
    private void mimic_setScrollPosition(int scroll) {
        if (scroll <= 0) return;
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
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                scrollbar.getClass().getMethod("setValue", int.class).invoke(scrollbar, scroll);
            }
        } catch (Exception ignored) {}
    }
}