package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import java.util.List;

public class Configs {

    //核心运行设置
    public static final ConfigBoolean ENABLE_MOD = new ConfigBoolean("litematica_container_filler.config.name.enableMod", true, "litematica_container_filler.config.comment.enableMod");
    public static final ConfigBoolean CONTINUOUS_FILL = new ConfigBoolean("litematica_container_filler.config.name.continuousFill", false, "litematica_container_filler.config.comment.continuousFill");
    public static final ConfigBoolean AREA_MODE = new ConfigBoolean("litematica_container_filler.config.name.areaMode", false, "litematica_container_filler.config.comment.areaMode");
    public static final ConfigInteger FILL_RADIUS = new ConfigInteger("litematica_container_filler.config.name.fillRadius", 5, 1, 32, "litematica_container_filler.config.comment.fillRadius");
    public static final ConfigInteger FILL_DELAY = new ConfigInteger("litematica_container_filler.config.name.fillDelay", 0, 0, 100, "litematica_container_filler.config.comment.fillDelay");
    public static final fi.dy.masa.malilib.config.options.ConfigStringList MATERIAL_REPLACEMENTS = new fi.dy.masa.malilib.config.options.ConfigStringList("litematica_container_filler.config.name.materialReplacements", com.google.common.collect.ImmutableList.of(), "litematica_container_filler.config.comment.materialReplacements");

    //数据同步设置
    public static final ConfigBoolean ENABLE_DATA_SYNC = new ConfigBoolean("litematica_container_filler.config.name.enableDataSync", true, "litematica_container_filler.config.comment.enableDataSync");
    public static final ConfigBoolean ENABLE_OP_NBT_QUERY = new ConfigBoolean("litematica_container_filler.config.name.enableOpNbtQuery", true, "litematica_container_filler.config.comment.enableOpNbtQuery");

    //自动物流设置
    public static final ConfigBoolean ENABLE_QS_EXTRACTION = new ConfigBoolean("litematica_container_filler.config.name.enableQsExtraction", true, "litematica_container_filler.config.comment.enableQsExtraction");
    public static final ConfigBoolean AUTO_STASH_ITEMS = new ConfigBoolean("litematica_container_filler.config.name.autoStashItems", true, "litematica_container_filler.config.comment.autoStashItems");
    public static final ConfigBoolean DROP_EXTRACTED_ITEMS = new ConfigBoolean("litematica_container_filler.config.name.dropExtractedItems", false, "litematica_container_filler.config.comment.dropExtractedItems");
    public static final ConfigBoolean ENABLE_SAFETY_DELAY = new ConfigBoolean("litematica_container_filler.config.name.enableSafetyDelay", true, "litematica_container_filler.config.comment.enableSafetyDelay");

    //高亮渲染基础设置
    public static final ConfigBoolean HIGHLIGHT_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.highlightContainers", true, "litematica_container_filler.config.comment.highlightContainers");
    public static final ConfigBoolean HIGHLIGHT_XRAY = new ConfigBoolean("litematica_container_filler.config.name.highlightXray", true, "litematica_container_filler.config.comment.highlightXray");
    public static final ConfigInteger RENDER_RADIUS = new ConfigInteger("litematica_container_filler.config.name.renderRadius", 15, 1, 64, "litematica_container_filler.config.comment.renderRadius");
    public static final ConfigBoolean SYNC_LITE_LAYER = new ConfigBoolean("litematica_container_filler.config.name.syncLiteLayer", true, "litematica_container_filler.config.comment.syncLiteLayer");
    public static final ConfigBoolean HIDE_COMPLETED_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.hideCompletedContainers", true, "litematica_container_filler.config.comment.hideCompletedContainers");

    //高亮颜色配置
    public static final ConfigColor HIGHLIGHT_COLOR_UNFILLED = new ConfigColor("litematica_container_filler.config.name.highlightColorUnfilled", "0x8033CCFF", "litematica_container_filler.config.comment.highlightColorUnfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_PARTIAL = new ConfigColor("litematica_container_filler.config.name.highlightColorPartial", "0x80FFFF00", "litematica_container_filler.config.comment.highlightColorPartial");
    public static final ConfigColor HIGHLIGHT_COLOR_OVERFILLED = new ConfigColor("litematica_container_filler.config.name.highlightColorOverfilled", "0x80FF00FF", "litematica_container_filler.config.comment.highlightColorOverfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_WRONG = new ConfigColor("litematica_container_filler.config.name.highlightColorWrong", "0x80FF0000", "litematica_container_filler.config.comment.highlightColorWrong");
    public static final ConfigColor HIGHLIGHT_COLOR_SATISFIED = new ConfigColor("litematica_container_filler.config.name.highlightColorSatisfied", "0x8033FF33", "litematica_container_filler.config.comment.highlightColorSatisfied");
    public static final ConfigColor HIGHLIGHT_COLOR_UNKNOWN = new ConfigColor("litematica_container_filler.config.name.highlightColorUnknown", "0x80FFA500", "litematica_container_filler.config.comment.highlightColorUnknown");

    public static final List<IConfigBase> OPTIONS;

    static {
        ImmutableList.Builder<IConfigBase> builder = ImmutableList.builder();

        // 核心
        builder.add(ENABLE_MOD, CONTINUOUS_FILL, AREA_MODE, FILL_RADIUS, FILL_DELAY, MATERIAL_REPLACEMENTS);
        // 数据
        builder.add(ENABLE_DATA_SYNC, ENABLE_OP_NBT_QUERY);

        // 物流
        // 只在检测到安装了 Quick Shulker 时，才向菜单中加入该选项
        if (DependencyChecker.HAS_QUICK_SHULKER) {
            builder.add(ENABLE_QS_EXTRACTION);
        }
        builder.add(AUTO_STASH_ITEMS, DROP_EXTRACTED_ITEMS, ENABLE_SAFETY_DELAY);

        // 渲染
        builder.add(HIGHLIGHT_CONTAINERS, HIGHLIGHT_XRAY, RENDER_RADIUS, SYNC_LITE_LAYER, HIDE_COMPLETED_CONTAINERS);
        // 颜色
        builder.add(HIGHLIGHT_COLOR_UNFILLED, HIGHLIGHT_COLOR_PARTIAL, HIGHLIGHT_COLOR_OVERFILLED,
                HIGHLIGHT_COLOR_WRONG, HIGHLIGHT_COLOR_SATISFIED, HIGHLIGHT_COLOR_UNKNOWN);

        OPTIONS = builder.build();
    }
}