package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import java.util.List;

public class Hotkeys {
    public static final ConfigHotkey OPEN_CONFIG_GUI    = new ConfigHotkey("openConfigGui", "L,C", "openConfigGui");
    public static final ConfigHotkey FILL_CONTAINER     = new ConfigHotkey("fillContainer", "V", "fillContainer");
    public static final ConfigHotkey TOOL_TRIGGER        = new ConfigHotkey("toolTrigger", "", "toolTrigger");
    public static final ConfigHotkey TOOL_SWITCH_MODE    = new ConfigHotkey("toolSwitchMode", "", "toolSwitchMode");
    public static final ConfigHotkey TOOL_SWITCH_PREVIOUS = new ConfigHotkey("toolSwitchPrevious", "", "toolSwitchPrevious");
    public static final ConfigHotkey TOOL_CLOSE_ALL      = new ConfigHotkey("toolCloseAll", "", "toolCloseAll");
    public static final ConfigHotkey CYCLE_MANUAL_OVERRIDE = new ConfigHotkey("cycleManualOverride", "", "cycleManualOverride");
    public static final ConfigHotkey CLEAR_MANUAL_OVERRIDES = new ConfigHotkey("clearManualOverrides", "", "clearManualOverrides");

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_CONFIG_GUI,
            FILL_CONTAINER,
            TOOL_TRIGGER,
            TOOL_SWITCH_MODE,
            TOOL_SWITCH_PREVIOUS,
            TOOL_CLOSE_ALL,
            CYCLE_MANUAL_OVERRIDE,
            CLEAR_MANUAL_OVERRIDES
    );
}
