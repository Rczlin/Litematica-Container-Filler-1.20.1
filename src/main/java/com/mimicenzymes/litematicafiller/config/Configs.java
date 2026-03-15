package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import java.util.List;

public class Configs {
    public static final ConfigBoolean ENABLE_MOD = new ConfigBoolean("litematica_container_filler.config.name.enableMod", true, "litematica_container_filler.config.comment.enableMod");
    public static final ConfigBoolean CONTINUOUS_FILL = new ConfigBoolean("litematica_container_filler.config.name.continuousFill", false, "litematica_container_filler.config.comment.continuousFill");
    public static final ConfigBoolean AREA_MODE = new ConfigBoolean("litematica_container_filler.config.name.areaMode", false, "litematica_econtainer_filler.config.comment.areaMode");
    public static final ConfigInteger FILL_RADIUS = new ConfigInteger("litematica_container_filler.config.name.fillRadius", 5, 1, 32, "litematica_container_filler.config.comment.fillRadius");
    public static final ConfigInteger RENDER_RADIUS = new ConfigInteger("litematica_container_filler.config.name.renderRadius", 15, 1, 64, "litematica_container_filler.config.comment.renderRadius");
    public static final ConfigBoolean SYNC_LITE_LAYER = new ConfigBoolean("litematica_container_filler.config.name.syncLiteLayer", true, "litematica_container_filler.config.comment.syncLiteLayer");
    public static final ConfigBoolean HIDE_COMPLETED_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.hideCompletedContainers", true, "litematica_container_filler.config.comment.hideCompletedContainers");
    public static final ConfigBoolean ENABLE_DATA_SYNC = new ConfigBoolean("litematica_container_filler.config.name.enableDataSync", true, "litematica_container_filler.config.comment.enableDataSync");
    public static final ConfigBoolean HIGHLIGHT_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.highlightContainers", true, "litematica_container_filler.config.comment.highlightContainers");
    public static final ConfigBoolean HIGHLIGHT_XRAY = new ConfigBoolean("litematica_container_filler.config.name.highlightXray", true, "litematica_container_filler.config.comment.highlightXray");
    public static final ConfigColor HIGHLIGHT_COLOR = new ConfigColor("litematica_container_filler.config.name.highlightColor", "0x808B4513", "litematica_container_filler.config.comment.highlightColor");
    public static final ConfigInteger FILL_DELAY = new ConfigInteger("litematica_container_filler.config.name.fillDelay", 0, 0, 100, "litematica_container_filler.config.comment.fillDelay");
    public static final ConfigBoolean ENABLE_QS_EXTRACTION = new ConfigBoolean("litematica_container_filler.config.name.enableQsExtraction", true, "litematica_container_filler.config.comment.enableQsExtraction");
    public static final ConfigColor HIGHLIGHT_COLOR_SATISFIED = new ConfigColor("litematica_container_filler.config.name.highlightColorSatisfied", "0x8033FF33", "litematica_container_filler.config.comment.highlightColorSatisfied");
    public static final ConfigColor HIGHLIGHT_COLOR_UNKNOWN = new ConfigColor("litematica_container_filler.config.name.highlightColorUnknown", "0x80FFA500", "litematica_container_filler.config.comment.highlightColorUnknown");
    public static final ConfigBoolean AUTO_STASH_ITEMS = new ConfigBoolean("litematica_container_filler.config.name.autoStashItems", true, "litematica_container_filler.config.comment.autoStashItems");
    public static final ConfigBoolean ENABLE_OP_NBT_QUERY = new ConfigBoolean("litematica_container_filler.config.name.enableOpNbtQuery", true, "litematica_container_filler.config.comment.enableOpNbtQuery");

    public static final List<IConfigBase> OPTIONS = ImmutableList.of(
            ENABLE_MOD, CONTINUOUS_FILL, AREA_MODE, FILL_RADIUS, RENDER_RADIUS,
            SYNC_LITE_LAYER, HIDE_COMPLETED_CONTAINERS, ENABLE_DATA_SYNC,
            HIGHLIGHT_CONTAINERS, HIGHLIGHT_XRAY,
            HIGHLIGHT_COLOR, HIGHLIGHT_COLOR_SATISFIED, HIGHLIGHT_COLOR_UNKNOWN,
            FILL_DELAY, ENABLE_QS_EXTRACTION, AUTO_STASH_ITEMS
    );
}