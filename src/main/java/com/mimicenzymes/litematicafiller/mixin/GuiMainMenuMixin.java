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

        int x = 214;
        int y = 104;
        int btnWidth = 98;

        String btnText = StringUtils.translate("litematica_container_filler.gui.title.configs");

        ButtonGeneric configBtn = new ButtonGeneric(x, y, btnWidth, 20, btnText);

        this.addButton(configBtn, (btn, mouseButton) -> {
            GuiBase.openGui(new GuiConfigs(this));
        });
    }
}