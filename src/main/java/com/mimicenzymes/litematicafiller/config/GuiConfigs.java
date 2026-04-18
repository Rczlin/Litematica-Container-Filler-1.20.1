package com.mimicenzymes.litematicafiller.config;

import com.mimicenzymes.litematicafiller.Reference;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.FEATURE;

    public GuiConfigs(Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, "litematica_container_filler.gui.title.configs");
    }

    public GuiConfigs() {
        this(MinecraftClient.getInstance().currentScreen);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        for (Tab t : Tab.values()) {
            String tabName = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button." + t.name().toLowerCase());
            ButtonGeneric b = new ButtonGeneric(x, 26, -1, 20, tabName);
            b.setEnabled(tab != t);
            this.addButton(b, (button, mouseButton) -> { this.tab = t; this.initGui(); });
            x += b.getWidth() + 2;
        }
    }

    public void reset() {
        reCreateListWidget();
        Objects.requireNonNull(getListWidget()).resetScrollbarPosition();
        initGui();
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> list = new ArrayList<>();
        if (this.tab == Tab.FEATURE) {
            Configs.OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
        } else {
            Hotkeys.HOTKEY_LIST.forEach(h -> list.add(new ConfigOptionWrapper((IHotkey) h)));
        }
        return list;
    }

    public enum Tab { FEATURE, HOTKEYS }

    public record ButtonListener(Tab tab, GuiConfigs parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiConfigs.tab = this.tab;
            this.parent.reset();
        }
    }
}