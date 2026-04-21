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

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class GuiMaterialListMixin extends GuiBase {

    @Unique
    private boolean mimic_isMonitorRunning = false;

    @Unique
    private boolean mimic_needsCalculation = true;

    @Unique
    private List<MaterialListEntry> mimic_cachedVanillaList = null;

    @Unique
    private List<MaterialListEntry> mimic_lastInjectedList = null;

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

        ButtonGeneric toggleBtn = new ButtonGeneric(maxX + 1, targetY, 120, 20, text);

        this.addButton(toggleBtn, (button, mouseButton) -> {
            FillMaterialCalculator.listMode = (FillMaterialCalculator.listMode + 1) % 3;
            FillMaterialCalculator.isFillMode = (FillMaterialCalculator.listMode != 0);

            mimic_needsCalculation = true;
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
            });
            monitor.setDaemon(true);
            monitor.setName("LitematicaFiller-InjectionWatchdog");
            monitor.start();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void mimic_injectUsingOfficialApi() {
        try {
            Object materialListObj = null;
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().contains("MaterialList") && !f.getType().getSimpleName().contains("Widget")) {
                    f.setAccessible(true);
                    materialListObj = f.get(this);
                    break;
                }
            }
            if (!(materialListObj instanceof IMaterialList)) return;

            IMaterialList iMatList = (IMaterialList) materialListObj;
            List<MaterialListEntry> currentBaseList = null;

            Class<?> curr = materialListObj.getClass();
            while (curr != null && currentBaseList == null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getName().equals("materialListAll") || f.getName().equals("m_materials")) {
                        f.setAccessible(true);
                        Object listObj = f.get(materialListObj);
                        if (listObj instanceof List) {
                            currentBaseList = (List<MaterialListEntry>) listObj;
                        }
                        break;
                    }
                }
                curr = curr.getSuperclass();
            }
            if (currentBaseList == null) return;

            if (currentBaseList != mimic_lastInjectedList) {
                mimic_cachedVanillaList = new ArrayList<>(currentBaseList);
                mimic_needsCalculation = true;
            }

            if (mimic_cachedVanillaList == null) return;

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return;
            }

            List<MaterialListEntry> targetList;

            if (FillMaterialCalculator.listMode == 1) {
                targetList = FillMaterialCalculator.getCustomMaterialList(materialListObj);
            } else if (FillMaterialCalculator.listMode == 2) {
                targetList = FillMaterialCalculator.mergeLists(mimic_cachedVanillaList, FillMaterialCalculator.getCustomMaterialList(materialListObj));
            } else {
                targetList = new ArrayList<>(mimic_cachedVanillaList);
            }

            int scroll = mimic_getScrollPosition();
            iMatList.setMaterialListEntries(targetList);
            List<MaterialListEntry> newBaseList = null;
            Class<?> curr2 = materialListObj.getClass();
            while (curr2 != null && newBaseList == null) {
                for (java.lang.reflect.Field f : curr2.getDeclaredFields()) {
                    if (f.getName().equals("materialListAll") || f.getName().equals("m_materials")) {
                        f.setAccessible(true);
                        newBaseList = (List<MaterialListEntry>) f.get(materialListObj);
                        break;
                    }
                }
                curr2 = curr2.getSuperclass();
            }
            mimic_lastInjectedList = newBaseList != null ? newBaseList : targetList;

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