package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import com.google.common.collect.ImmutableList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class GuiMaterialListMixin extends GuiBase {

    // Direct @Shadow — no reflection needed for this field
    @Shadow @Final private MaterialListBase materialList;

    // Cached reflection for materialListAll (protected ImmutableList, no public setter for field-only writes)
    @Unique private static java.lang.reflect.Field mimic_materialListAllField = null;
    @Unique private static boolean mimic_reflectionInit = false;

    @Unique private boolean mimic_isMonitorRunning = false;
    @Unique private boolean mimic_needsCalculation = true;
    @Unique private List<MaterialListEntry> mimic_cachedVanillaList = null;
    @Unique private ImmutableList<MaterialListEntry> mimic_lastInjectedRef = null;
    @Unique private boolean mimic_isInjecting = false; // re-entrancy guard

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;

        // Always add the toggle button (initGui rebuilds all buttons every time)
        mimic_addToggleButton();

        // If this is a re-entrant call from setMaterialListEntries() → onTaskCompleted() → initGui(),
        // skip injection. Stats labels & column widths are already correct.
        if (mimic_isInjecting) return;

        mimic_needsCalculation = true;

        // Immediately inject if in container/merged mode (don't wait for watchdog)
        if (FillMaterialCalculator.listMode != 0) {
            mimic_injectSilently();
        }

        // Start watchdog thread (50ms interval for incremental container data loading)
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
                                    mimic_injectSilently();
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
    private void mimic_addToggleButton() {
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
                    if (btnObj instanceof fi.dy.masa.malilib.gui.button.ButtonBase btn) {
                        if (btn.getY() < 50) {
                            int rightEdge = btn.getX() + btn.getWidth();
                            if (rightEdge > maxX) {
                                maxX = rightEdge;
                                targetY = btn.getY();
                            }
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
            mimic_lastInjectedRef = null; // force re-detect vanilla list

            // Mode switch: use full API injection to rebuild widgets, column widths, etc.
            mimic_injectViaApi();
        });
    }

    /**
     * Full API injection: used for mode switches and initial load.
     * Triggers initGui() → full widget + stats + column width rebuild.
     * This is acceptable because the user is actively switching modes.
     */
    @Unique
    private void mimic_injectViaApi() {
        try {
            if (!(materialList instanceof IMaterialList iMatList)) return;
            if (mimic_cachedVanillaList == null) {
                ImmutableList<MaterialListEntry> current = mimic_readMaterialListAll();
                if (current != null) mimic_cachedVanillaList = new ArrayList<>(current);
            }
            if (mimic_cachedVanillaList == null) return;

            FillMaterialCalculator.calculate(this, true);
            mimic_needsCalculation = false;

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            mimic_isInjecting = true;
            try {
                iMatList.setMaterialListEntries(targetList);
            } finally {
                mimic_isInjecting = false;
            }

            // Track the new ImmutableList reference
            ImmutableList<MaterialListEntry> newRef = mimic_readMaterialListAll();
            mimic_lastInjectedRef = newRef;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Silent field injection: used by the watchdog for incremental updates.
     * Does NOT trigger initGui() → preserves text field focus and widget stability.
     *
     * This manually replicates setMaterialListEntries() logic:
     *   materialListAll = ImmutableList.copyOf(list)
     *   refreshPreFilteredList()  (public — respects ignored set)
     *   updateCounts()            (public — updates bottom stats)
     * but skips onTaskCompleted() → initGui().
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void mimic_injectSilently() {
        try {
            // Read current materialListAll to detect vanilla recalculations
            ImmutableList<MaterialListEntry> currentRef = mimic_readMaterialListAll();
            if (currentRef == null) return;

            // If the base list changed (Litematica recalculated, e.g. layer change),
            // re-cache the vanilla list
            if (currentRef != mimic_lastInjectedRef) {
                mimic_cachedVanillaList = new ArrayList<>(currentRef);
                mimic_needsCalculation = true;
            }

            if (mimic_cachedVanillaList == null) return;

            // Only recalculate when needed
            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return; // No new data, skip injection
            }

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            // Save scroll position
            int scroll = mimic_getScrollPosition();

            // Write materialListAll directly (no initGui triggered)
            mimic_writeMaterialListAll(ImmutableList.copyOf(targetList));

            // Replicate the rest of setMaterialListEntries() WITHOUT onTaskCompleted()
            materialList.refreshPreFilteredList(); // public — respects ignored set
            materialList.updateCounts();           // public — updates bottom stats

            // Track the new reference
            mimic_lastInjectedRef = mimic_readMaterialListAll();

            // Refresh widget display without full rebuild
            mimic_refreshWidget();

            // Restore scroll position
            mimic_setScrollPosition(scroll);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Build the target list based on current mode.
     */
    @Unique
    private List<MaterialListEntry> mimic_buildTargetList() {
        if (FillMaterialCalculator.listMode == 1) {
            return FillMaterialCalculator.getCustomMaterialList(materialList);
        } else if (FillMaterialCalculator.listMode == 2) {
            return FillMaterialCalculator.mergeLists(
                    mimic_cachedVanillaList,
                    FillMaterialCalculator.getCustomMaterialList(materialList));
        } else {
            return new ArrayList<>(mimic_cachedVanillaList);
        }
    }

    // ---- Reflection helpers (minimal, cached) ----

    @Unique
    private static void mimic_ensureReflection() {
        if (mimic_reflectionInit) return;
        mimic_reflectionInit = true;
        try {
            mimic_materialListAllField = MaterialListBase.class.getDeclaredField("materialListAll");
            mimic_materialListAllField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private ImmutableList<MaterialListEntry> mimic_readMaterialListAll() {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return null;
        try {
            return (ImmutableList<MaterialListEntry>) mimic_materialListAllField.get(materialList);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private void mimic_writeMaterialListAll(ImmutableList<MaterialListEntry> list) {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return;
        try {
            mimic_materialListAllField.set(materialList, list);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Refresh the widget's displayed entries without a full initGui() rebuild.
     */
    @Unique
    private void mimic_refreshWidget() {
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                widget.getClass().getMethod("refreshEntries").invoke(widget);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private Object mimic_getWidget() {
        try {
            Class<?> curr = this.getClass();
            while (curr != null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getName().equals("m_widget") || f.getName().equals("widget")) {
                        f.setAccessible(true);
                        return f.get(this);
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private int mimic_getScrollPosition() {
        try {
            Object widget = mimic_getWidget();
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
            Object widget = mimic_getWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                scrollbar.getClass().getMethod("setValue", int.class).invoke(scrollbar, scroll);
            }
        } catch (Exception ignored) {}
    }
}