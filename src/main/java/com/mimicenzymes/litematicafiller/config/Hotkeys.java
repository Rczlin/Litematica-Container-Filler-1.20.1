package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import java.util.List;

public class Hotkeys {
    public static final ConfigHotkey OPEN_CONFIG_GUI    = new ConfigHotkey("litematica_container_filler.hotkey.name.openConfigGui", "L,C", "litematica_container_filler.hotkey.comment.openConfigGui");
    public static final ConfigHotkey FILL_CONTAINER     = new ConfigHotkey("litematica_container_filler.hotkey.name.fillContainer", "V", "litematica_container_filler.hotkey.comment.fillContainer");
    public static final ConfigHotkey TOGGLE_CONTINUOUS  = new ConfigHotkey("litematica_container_filler.hotkey.name.toggleContinuous", "", "litematica_container_filler.hotkey.comment.toggleContinuous");
    public static final ConfigHotkey TOGGLE_MODE        = new ConfigHotkey("litematica_container_filler.hotkey.name.toggleMode", "", "litematica_container_filler.hotkey.comment.toggleMode");

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_CONFIG_GUI,
            FILL_CONTAINER,
            TOGGLE_CONTINUOUS,
            TOGGLE_MODE
    );
}