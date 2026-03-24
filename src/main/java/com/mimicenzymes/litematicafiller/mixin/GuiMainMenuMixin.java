package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class GuiMainMenuMixin extends GuiBase {

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    private void onInitGuiAddFillerButton(CallbackInfo ci) {

        int targetX = 214;
        int targetY = 104;
        int btnWidth = 98;

        try {
            java.util.List<?> buttons = null;
            for (java.lang.reflect.Field f : GuiBase.class.getDeclaredFields()) {
                if (f.getType() == java.util.List.class && f.getName().toLowerCase().contains("button")) {
                    f.setAccessible(true);
                    buttons = (java.util.List<?>) f.get(this);
                    break;
                }
            }

            if (buttons != null && !buttons.isEmpty()) {
                int maxY = -1;
                Object anchorBtn = null;

                for (Object btnObj : buttons) {
                    int btnY = (int) btnObj.getClass().getMethod("getY").invoke(btnObj);
                    int btnX = (int) btnObj.getClass().getMethod("getX").invoke(btnObj);
                    if (btnX > 120 && btnY > maxY) {
                        maxY = btnY;
                        anchorBtn = btnObj;
                    }
                }

                if (anchorBtn != null) {
                    targetX = (int) anchorBtn.getClass().getMethod("getX").invoke(anchorBtn);
                    targetY = maxY + 22;
                    btnWidth = (int) anchorBtn.getClass().getMethod("getWidth").invoke(anchorBtn);
                }
            }
        } catch (Exception ignored) {}

        String btnText = StringUtils.translate("litematica_container_filler.gui.title.configs");

        ButtonGeneric configBtn = new ButtonGeneric(targetX, targetY, btnWidth, 20, btnText);

        this.addButton(configBtn, (btn, mouseButton) -> {
            GuiBase.openGui(new GuiConfigs(this));
        });
    }
}