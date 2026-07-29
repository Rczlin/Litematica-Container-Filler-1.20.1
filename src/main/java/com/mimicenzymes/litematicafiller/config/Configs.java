package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.filter.ContainerFilterMode;
import com.mimicenzymes.litematicafiller.filter.ContainerFilterScope;
import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.mimicenzymes.litematicafiller.input.InputHandler;
import com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode;
import com.mimicenzymes.litematicafiller.tool.ContainerToolMode;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.util.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.util.List;

public class Configs implements IConfigHandler {

    private static final Configs INSTANCE = new Configs();

    private static final String CONFIG_FILE_NAME = "litematica_container_filler.json";

    //核心运行设置
    public static final ConfigBooleanHotkeyed ENABLE_MOD            = new ConfigBooleanHotkeyed("enableMod", true, "", "enableMod");
    public static final ConfigBooleanHotkeyed WORKING_STATE         = new ConfigBooleanHotkeyed("workingState", false, "", "workingState");
    private static final ConfigBoolean CONTINUOUS_FILL              = new ConfigBoolean("continuousFill", false, "continuousFill");
    private static final ConfigBoolean AREA_MODE                    = new ConfigBoolean("areaMode", false, "areaMode");
    private static final ConfigBoolean LEGACY_ENABLE_CARPET_LARGE_BARRELS = new ConfigBoolean("enableCarpetLargeBarrels", false, "enableCarpetLargeBarrels");
    public static final ConfigBoolean DEBUG_MODE                    = new ConfigBoolean("debugMode", false, "debugMode");
    public static final ConfigBoolean DEBUG_LOG_PCA                 = new ConfigBoolean("debugLogPca", true, "debugLogPca");
    public static final ConfigBoolean DEBUG_LOG_FILL_TASK           = new ConfigBoolean("debugLogFillTask", true, "debugLogFillTask");
    public static final ConfigBoolean DEBUG_LOG_FILL_PHASE          = new ConfigBoolean("debugLogFillPhase", true, "debugLogFillPhase");
    public static final ConfigBoolean DEBUG_LOG_PERF                = new ConfigBoolean("debugLogPerf", true, "debugLogPerf");
    public static final ConfigBoolean DEBUG_LOG_SCAN                = new ConfigBoolean("debugLogScan", true, "debugLogScan");
    public static final ConfigOptionList CARPET_LARGE_BARREL_MODE   = new ConfigOptionList("carpetLargeBarrelMode", CarpetLargeBarrelMode.OFF, "carpetLargeBarrelMode");
    public static final ConfigInteger FILL_RADIUS                   = new ConfigInteger("fillRadius", 5, 0, 1024, "fillRadius");
    public static final ConfigDouble INTERACTION_REACH              = new ConfigDouble("interactionReach", 5.0D, 0.0D, 1024.0D, "interactionReach");
    public static final ConfigInteger FILL_DELAY                    = new ConfigInteger("fillDelay", 0, 0, 100, "fillDelay");
    public static final ConfigBoolean ENABLE_SAFETY_DELAY           = new ConfigBoolean("enableSafetyDelay", true, "enableSafetyDelay");
    public static final ConfigBoolean ENABLE_FILL_STATE_PROTECTION  = new ConfigBoolean("enableFillStateProtection", true, "enableFillStateProtection");
    public static final ConfigBoolean RATE_LIMIT_CLICK_PACKETS      = new ConfigBoolean("rateLimitClickPackets", false, "rateLimitClickPackets");
    public static final ConfigInteger CLICK_PACKET_RATE_LIMIT        = new ConfigInteger("clickPacketRateLimit", 4, 1, 1024, "clickPacketRateLimit");
    public static final ConfigOptionList CONTAINER_FILTER_MODE      = new ConfigOptionList("containerFilterMode", ContainerFilterMode.DISABLED, "containerFilterMode");
    public static final ConfigOptionList CONTAINER_FILTER_SCOPE     = new ConfigOptionList("containerFilterScope", ContainerFilterScope.BOTH, "containerFilterScope");
    public static final ConfigStringList CONTAINER_FILTER_LIST      = new ConfigStringList("containerFilterList", ImmutableList.of("minecraft:chest", "minecraft:barrel", "minecraft:trapped_chest", "minecraft:shulker_box", "minecraft:*_shulker_box", "minecraft:crafter", "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand"), "containerFilterList");
    public static final ConfigStringList MATERIAL_REPLACEMENTS      = new ConfigStringList("materialReplacements", ImmutableList.of(), "materialReplacements");

    //数据同步设置
    public static final ConfigBooleanHotkeyed ENABLE_DATA_SYNC      = new ConfigBooleanHotkeyed("enableDataSync", true, "", "enableDataSync");
    public static final ConfigBoolean ENABLE_OP_NBT_QUERY           = new ConfigBoolean("enableOpNbtQuery", true, "enableOpNbtQuery");
    public static final ConfigInteger CACHE_ENTRY_LIMIT             = new ConfigInteger("cacheEntryLimit", 32768, 256, 262144, "cacheEntryLimit");
    public static final ConfigInteger CACHE_TTL                     = new ConfigInteger("cacheTtl", 300, 10, 36000, "cacheTtl");
    public static final ConfigInteger PCA_SYNC_REQUESTS_PER_TICK    = new ConfigInteger("pcaSyncRequestsPerTick", 16, 0, 2048, "pcaSyncRequestsPerTick");
    public static final ConfigInteger PCA_SYNC_RETRY_COOLDOWN_TICKS = new ConfigInteger("pcaSyncRetryCooldownTicks", 20, 0, 1200, "pcaSyncRetryCooldownTicks");
    public static final ConfigInteger HIGHLIGHT_DATA_REQUEST_BUDGET = new ConfigInteger("highlightDataRequestBudget", 128, 1, 2048, "highlightDataRequestBudget");

    //自动物流设置
    public static final ConfigBoolean ENABLE_CREATIVE_FILL          = new ConfigBoolean("creativeFill", true, "creativeFill");
    public static final ConfigBoolean ENABLE_QS_EXTRACTION          = new ConfigBoolean("enableQsExtraction", true, "enableQsExtraction");
    public static final ConfigOptionList QUICK_SHULKER_OPEN_MODE    = new ConfigOptionList("quickShulkerOpenMode", QuickShulkerOpenMode.INVOKE, "quickShulkerOpenMode");
    public static final ConfigBoolean MATCH_SHULKER_BOXES_BY_CONTENT = new ConfigBoolean("matchShulkerBoxesByContent", false, "matchShulkerBoxesByContent");
    private static final ConfigBoolean AUTO_STASH_ITEMS             = new ConfigBoolean("autoStashItems", true, "autoStashItems");
    public static final ConfigBoolean STORE_ORDERLY                 = new ConfigBoolean("storeOrderly", true, "storeOrderly");
    public static final ConfigBoolean DROP_EXTRACTED_ITEMS          = new ConfigBoolean("dropExtractedItems", false, "dropExtractedItems");
    public static final ConfigBoolean DROP_ITEMS_FROM_EMPTY_SCHEMATIC_CONTAINERS = new ConfigBoolean("dropItemsFromEmptySchematicContainers", false, "dropItemsFromEmptySchematicContainers");
    private static final ConfigBoolean HIDE_FILLER_GUI              = new ConfigBoolean("hideFillerGui", true, "hideFillerGui");
    public static final ConfigBoolean HIDE_PROJECTION_FILL_GUI      = new ConfigBoolean("hideProjectionFillGui", true, "hideProjectionFillGui");
    public static final ConfigBoolean TOOL_ENABLED                  = new ConfigBoolean("toolEnabled", false, "toolEnabled");
    public static final ConfigOptionList CONTAINER_TOOL_MODE        = new ConfigOptionList("containerToolMode", ContainerToolMode.CLEAR, "containerToolMode");
    public static final ConfigBoolean ENABLE_SYNC_TOOL_QS_EXTRACTION = new ConfigBoolean("enableSyncToolQsExtraction", true, "enableSyncToolQsExtraction");
    public static final ConfigBoolean ENABLE_TOOL_HOLD_REPEAT       = new ConfigBoolean("enableToolHoldRepeat", false, "enableToolHoldRepeat");
    public static final ConfigInteger TOOL_REPEAT_SAME_CONTAINER_COOLDOWN = new ConfigInteger("toolRepeatSameContainerCooldown", 20, 0, 200, "toolRepeatSameContainerCooldown");
    public static final ConfigInteger TOOL_FILL_FULL_THRESHOLD       = new ConfigInteger("toolFillFullThreshold", 2, 1, 36, "toolFillFullThreshold");
    public static final ConfigBoolean COLLECT_MATERIAL_LIST_ITEMS_RETAIN = new ConfigBoolean("collectMaterialListItemsRetain", false, "collectMaterialListItemsRetain");
    public static final ConfigInteger COLLECT_MATERIAL_LIST_ITEMS_RETAIN_AMOUNT = new ConfigInteger("collectMaterialListItemsRetainAmount", 1, 1, 63, "collectMaterialListItemsRetainAmount");
    public static final ConfigBoolean COLLECT_MATERIAL_LIST_ITEMS_CLOSE_GUI = new ConfigBoolean("collectMaterialListItemsCloseGui", true, "collectMaterialListItemsCloseGui");
    public static final ConfigOptionList CONTAINER_CLEAR_OUTPUT_MODE = new ConfigOptionList("containerClearOutputMode", ContainerClearOutputMode.DROP, "containerClearOutputMode");
    public static final ConfigBoolean HIDE_TOOL_GUI                 = new ConfigBoolean("hideToolGui", true, "hideToolGui");
    public static final ConfigBooleanHotkeyed ENABLE_TOOL_HUD       = new ConfigBooleanHotkeyed("enableToolHud", false, "", "enableToolHud");
    public static final ConfigBoolean TOOL_HUD_BORDER               = new ConfigBoolean("toolHudBorder", false, "toolHudBorder");
    public static final ConfigOptionList TOOL_HUD_STYLE             = new ConfigOptionList("toolHudStyle", ToolHudStyle.FIXED_CARD, "toolHudStyle");
    public static final ConfigDouble TOOL_HUD_OPACITY               = new ConfigDouble("toolHudOpacity", 0.52D, 0.1D, 1.0D, "toolHudOpacity");
    public static final ConfigDouble TOOL_HUD_SMOOTHING             = new ConfigDouble("toolHudSmoothing", 0.22D, 0.05D, 0.8D, "toolHudSmoothing");
    public static final ConfigInteger TOOL_HUD_OFFSET               = new ConfigInteger("toolHudOffset", 34, 12, 120, "toolHudOffset");
    public static final ConfigInteger TOOL_HUD_SCALE                = new ConfigInteger("toolHudScale", 80, 70, 150, "toolHudScale");
    public static final ConfigInteger TOOL_HUD_ICON_SCALE           = new ConfigInteger("toolHudIconScale", 62, 50, 150, "toolHudIconScale");
    public static final ConfigInteger TOOL_HUD_CUSTOM_X             = new ConfigInteger("toolHudCustomX", 100, -1000, 1000, "toolHudCustomX");
    public static final ConfigInteger TOOL_HUD_CUSTOM_Y             = new ConfigInteger("toolHudCustomY", -110, -1000, 1000, "toolHudCustomY");
    public static final ConfigInteger TOOL_HUD_FRAME_RATE           = new ConfigInteger("toolHudFrameRate", 30, 0, 240, "toolHudFrameRate");

    // Container tool hotkeys
    public static final ConfigBooleanHotkeyed TOOL_CLEAR_MODE        = new ConfigBooleanHotkeyed("toolClearMode", false, "", "toolClearMode");
    public static final ConfigBooleanHotkeyed TOOL_FILL_FULL_MODE    = new ConfigBooleanHotkeyed("toolFillFullMode", false, "", "toolFillFullMode");
    public static final ConfigBooleanHotkeyed TOOL_COPY_MODE         = new ConfigBooleanHotkeyed("toolCopyMode", false, "", "toolCopyMode");

    //高亮渲染基础设置
    public static final ConfigBooleanHotkeyed HIGHLIGHT_CONTAINERS  = new ConfigBooleanHotkeyed("highlightContainers", true, "", "highlightContainers");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_XRAY        = new ConfigBooleanHotkeyed("highlightXray", false, "", "highlightXray");
    public static final ConfigInteger RENDER_RADIUS                 = new ConfigInteger("renderRadius", 0, 0, 1024, "renderRadius");
    public static final ConfigBooleanHotkeyed SYNC_LITE_LAYER       = new ConfigBooleanHotkeyed("syncLiteLayer", true, "", "syncLiteLayer");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS = new ConfigBooleanHotkeyed("highlightEmptySchematicContainers", true, "", "highlightEmptySchematicContainers");
    public static final ConfigBooleanHotkeyed RENDER_STATE_UNFILLED = new ConfigBooleanHotkeyed("renderStateUnfilled", true, "", "renderStateUnfilled");
    public static final ConfigBooleanHotkeyed RENDER_STATE_PARTIAL  = new ConfigBooleanHotkeyed("renderStatePartial", true, "", "renderStatePartial");
    public static final ConfigBooleanHotkeyed RENDER_STATE_OVERFILLED = new ConfigBooleanHotkeyed("renderStateOverfilled", true, "", "renderStateOverfilled");
    public static final ConfigBooleanHotkeyed RENDER_STATE_WRONG    = new ConfigBooleanHotkeyed("renderStateWrong", true, "", "renderStateWrong");
    public static final ConfigBooleanHotkeyed RENDER_STATE_SATISFIED = new ConfigBooleanHotkeyed("renderStateSatisfied", true, "", "renderStateSatisfied");
    public static final ConfigBooleanHotkeyed RENDER_STATE_UNKNOWN  = new ConfigBooleanHotkeyed("renderStateUnknown", true, "", "renderStateUnknown");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_UNPLACED_CONTAINERS = new ConfigBooleanHotkeyed("highlightUnplacedContainers", true, "", "highlightUnplacedContainers");
    public static final ConfigBooleanHotkeyed RENDER_STATE_GLASS    = new ConfigBooleanHotkeyed("renderStateGlass", true, "", "renderStateGlass");
    public static final ConfigBooleanHotkeyed RENDER_STATE_TOP_PLATE = new ConfigBooleanHotkeyed("renderStateTopPlate", true, "", "renderStateTopPlate");
    public static final ConfigBooleanHotkeyed RENDER_FILLING_ARROW  = new ConfigBooleanHotkeyed("renderFillingArrow", true, "", "renderFillingArrow");
    public static final ConfigBooleanHotkeyed RENDER_QUEUED_SPINNER = new ConfigBooleanHotkeyed("renderQueuedSpinner", true, "", "renderQueuedSpinner");
    public static final ConfigBooleanHotkeyed RENDER_MISSING_MATERIAL_MARKER = new ConfigBooleanHotkeyed("renderMissingMaterialMarker", true, "", "renderMissingMaterialMarker");
    public static final ConfigInteger HIGHLIGHT_SCAN_BUDGET         = new ConfigInteger("highlightScanBudget", 256, 1, 2048, "highlightScanBudget");
    public static final ConfigInteger MAX_QUEUED_RENDER_OVERLAYS    = new ConfigInteger("maxQueuedRenderOverlays", 20, 0, 256, "maxQueuedRenderOverlays");
    public static final ConfigInteger TASK_OVERLAY_LINGER_TICKS     = new ConfigInteger("taskOverlayLingerTicks", 40, 0, 200, "taskOverlayLingerTicks");
    public static final ConfigDouble HIGHLIGHT_GLASS_ALPHA_MULTIPLIER = new ConfigDouble("highlightGlassAlphaMultiplier", 0.24D, 0.0D, 1.0D, "highlightGlassAlphaMultiplier");
    public static final ConfigDouble HIGHLIGHT_TOP_PLATE_SIZE       = new ConfigDouble("highlightTopPlateSize", 0.64D, 0.1D, 1.2D, "highlightTopPlateSize");
    public static final ConfigDouble TASK_OVERLAY_SCALE             = new ConfigDouble("taskOverlayScale", 1.0D, 0.25D, 3.0D, "taskOverlayScale");
    public static final ConfigInteger TASK_MARKER_ANIMATION_FPS     = new ConfigInteger("taskMarkerAnimationFps", 30, 0, 240, "taskMarkerAnimationFps");

    //高亮颜色配置
    public static final ConfigColor HIGHLIGHT_COLOR_UNFILLED        = new ConfigColor("highlightColorUnfilled", "0x806E5CFF", "highlightColorUnfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_PARTIAL         = new ConfigColor("highlightColorPartial", "0x80FFB02E", "highlightColorPartial");
    public static final ConfigColor HIGHLIGHT_COLOR_OVERFILLED      = new ConfigColor("highlightColorOverfilled", "0x80FF3EA5", "highlightColorOverfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_WRONG           = new ConfigColor("highlightColorWrong", "0x80FF5A45", "highlightColorWrong");
    public static final ConfigColor HIGHLIGHT_COLOR_SATISFIED       = new ConfigColor("highlightColorSatisfied", "0x8044FFB2", "highlightColorSatisfied");
    public static final ConfigColor HIGHLIGHT_COLOR_UNKNOWN         = new ConfigColor("highlightColorUnknown", "0x80B66DFF", "highlightColorUnknown");
    public static final ConfigColor HIGHLIGHT_COLOR_UNPLACED        = new ConfigColor("highlightColorUnplaced", "0x8097A6C7", "highlightColorUnplaced");
    public static final ConfigColor HIGHLIGHT_COLOR_FILLING         = new ConfigColor("highlightColorFilling", "0xD00DFFF2", "highlightColorFilling");
    public static final ConfigColor HIGHLIGHT_COLOR_QUEUED          = new ConfigColor("highlightColorQueued", "0xC0FFB02E", "highlightColorQueued");
    public static final ConfigColor HIGHLIGHT_COLOR_MISSING_MATERIAL = new ConfigColor("highlightColorMissingMaterial", "0xD0FF385C", "highlightColorMissingMaterial");

    public static final List<IConfigBase> OPTIONS;
    public static final List<IConfigBase> CORE_OPTIONS;
    public static final List<IConfigBase> DEBUG_OPTIONS;
    public static final List<IConfigBase> DATA_OPTIONS;
    public static final List<IConfigBase> LOGISTICS_OPTIONS;
    public static final List<IConfigBase> FILTER_OPTIONS;
    public static final List<IConfigBase> TOOL_OPTIONS;
    public static final List<IConfigBase> RENDER_OPTIONS;
    public static final List<ConfigBooleanHotkeyed> BOOLEAN_HOTKEY_OPTIONS;
    private static final List<IConfigBase> LEGACY_OPTIONS;

    static {
        CORE_OPTIONS = ImmutableList.of(
                ENABLE_MOD,
                WORKING_STATE,
                FILL_RADIUS,
                INTERACTION_REACH,
                FILL_DELAY,
                ENABLE_SAFETY_DELAY,
                ENABLE_FILL_STATE_PROTECTION,
                CARPET_LARGE_BARREL_MODE,
                MATERIAL_REPLACEMENTS,
                DEBUG_MODE
        );

        DEBUG_OPTIONS = ImmutableList.of(
                DEBUG_LOG_PCA,
                DEBUG_LOG_FILL_TASK,
                DEBUG_LOG_FILL_PHASE,
                DEBUG_LOG_PERF,
                DEBUG_LOG_SCAN
        );

        DATA_OPTIONS = ImmutableList.of(
                ENABLE_DATA_SYNC,
                ENABLE_OP_NBT_QUERY,
                CACHE_ENTRY_LIMIT,
                CACHE_TTL,
                PCA_SYNC_REQUESTS_PER_TICK,
                PCA_SYNC_RETRY_COOLDOWN_TICKS,
                HIGHLIGHT_DATA_REQUEST_BUDGET,
                RATE_LIMIT_CLICK_PACKETS,
                CLICK_PACKET_RATE_LIMIT
        );

        FILTER_OPTIONS = ImmutableList.of(
                CONTAINER_FILTER_MODE,
                CONTAINER_FILTER_SCOPE,
                CONTAINER_FILTER_LIST
        );

        LOGISTICS_OPTIONS = ImmutableList.of(
                ENABLE_CREATIVE_FILL,
                ENABLE_QS_EXTRACTION,
                QUICK_SHULKER_OPEN_MODE,
                MATCH_SHULKER_BOXES_BY_CONTENT,
                STORE_ORDERLY,
                DROP_EXTRACTED_ITEMS,
                DROP_ITEMS_FROM_EMPTY_SCHEMATIC_CONTAINERS,
                HIDE_PROJECTION_FILL_GUI
        );

        TOOL_OPTIONS = ImmutableList.of(
                TOOL_ENABLED,
                CONTAINER_TOOL_MODE,
                ENABLE_SYNC_TOOL_QS_EXTRACTION,
                ENABLE_TOOL_HOLD_REPEAT,
                TOOL_REPEAT_SAME_CONTAINER_COOLDOWN,
                TOOL_FILL_FULL_THRESHOLD,
                COLLECT_MATERIAL_LIST_ITEMS_RETAIN,
                COLLECT_MATERIAL_LIST_ITEMS_RETAIN_AMOUNT,
                COLLECT_MATERIAL_LIST_ITEMS_CLOSE_GUI,
                CONTAINER_CLEAR_OUTPUT_MODE,
                HIDE_TOOL_GUI,
                ENABLE_TOOL_HUD,
                TOOL_HUD_BORDER,
                TOOL_HUD_STYLE,
                TOOL_HUD_OPACITY,
                TOOL_HUD_SMOOTHING,
                TOOL_HUD_OFFSET,
                TOOL_HUD_SCALE,
                TOOL_HUD_ICON_SCALE,
                TOOL_HUD_CUSTOM_X,
                TOOL_HUD_CUSTOM_Y,
                TOOL_HUD_FRAME_RATE
        );

        RENDER_OPTIONS = ImmutableList.of(
                HIGHLIGHT_CONTAINERS,
                HIGHLIGHT_XRAY,
                RENDER_RADIUS,
                SYNC_LITE_LAYER,
                HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS,
                RENDER_STATE_UNFILLED,
                RENDER_STATE_PARTIAL,
                RENDER_STATE_OVERFILLED,
                RENDER_STATE_WRONG,
                RENDER_STATE_SATISFIED,
                RENDER_STATE_UNKNOWN,
                HIGHLIGHT_UNPLACED_CONTAINERS,
                RENDER_STATE_GLASS,
                RENDER_STATE_TOP_PLATE,
                RENDER_FILLING_ARROW,
                RENDER_QUEUED_SPINNER,
                RENDER_MISSING_MATERIAL_MARKER,
                HIGHLIGHT_SCAN_BUDGET,
                MAX_QUEUED_RENDER_OVERLAYS,
                TASK_OVERLAY_LINGER_TICKS,
                HIGHLIGHT_GLASS_ALPHA_MULTIPLIER,
                HIGHLIGHT_TOP_PLATE_SIZE,
                TASK_OVERLAY_SCALE,
                TASK_MARKER_ANIMATION_FPS,
                HIGHLIGHT_COLOR_UNFILLED,
                HIGHLIGHT_COLOR_PARTIAL,
                HIGHLIGHT_COLOR_OVERFILLED,
                HIGHLIGHT_COLOR_WRONG,
                HIGHLIGHT_COLOR_SATISFIED,
                HIGHLIGHT_COLOR_UNKNOWN,
                HIGHLIGHT_COLOR_UNPLACED,
                HIGHLIGHT_COLOR_FILLING,
                HIGHLIGHT_COLOR_QUEUED,
                HIGHLIGHT_COLOR_MISSING_MATERIAL
        );

        BOOLEAN_HOTKEY_OPTIONS = ImmutableList.of(
                ENABLE_MOD,
                ENABLE_DATA_SYNC,
                ENABLE_TOOL_HUD,
                HIGHLIGHT_CONTAINERS,
                HIGHLIGHT_XRAY,
                SYNC_LITE_LAYER,
                HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS,
                RENDER_STATE_UNFILLED,
                RENDER_STATE_PARTIAL,
                RENDER_STATE_OVERFILLED,
                RENDER_STATE_WRONG,
                RENDER_STATE_SATISFIED,
                RENDER_STATE_UNKNOWN,
                HIGHLIGHT_UNPLACED_CONTAINERS,
                RENDER_STATE_GLASS,
                RENDER_STATE_TOP_PLATE,
                RENDER_FILLING_ARROW,
                RENDER_QUEUED_SPINNER,
                RENDER_MISSING_MATERIAL_MARKER
        );

        LEGACY_OPTIONS = ImmutableList.of(CONTINUOUS_FILL, AREA_MODE, LEGACY_ENABLE_CARPET_LARGE_BARRELS, AUTO_STASH_ITEMS, HIDE_FILLER_GUI);

        ImmutableList.Builder<IConfigBase> builder = ImmutableList.builder();
        builder.addAll(CORE_OPTIONS);
        builder.addAll(DEBUG_OPTIONS);
        builder.addAll(DATA_OPTIONS);
        builder.addAll(LOGISTICS_OPTIONS);
        builder.addAll(FILTER_OPTIONS);
        builder.addAll(TOOL_OPTIONS);
        builder.addAll(RENDER_OPTIONS);
        OPTIONS = builder.build();
    }

    @Override
    public void load() {
        File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);
        if (file.exists() && file.canRead()) {
            JsonElement element = JsonUtils.parseJsonFile(file);
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Features", Configs.LEGACY_OPTIONS);
                ConfigUtils.readConfigBase(root, "Features", Configs.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
                if (CONTINUOUS_FILL.getBooleanValue()) {
                    WORKING_STATE.setBooleanValue(true);
                }
                migrateLegacyLargeBarrelConfig(root);
                migrateLegacyAutoStashConfig(root);
                migrateLegacyHiddenGuiConfig(root);
                validateConditionalOptions();
            }
        }
    }

    private static void validateConditionalOptions() {
        if (CONTAINER_TOOL_MODE.getOptionListValue() == ContainerToolMode.PACK && !ContainerToolMode.isPackingAvailable()) {
            CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
            TOOL_ENABLED.setBooleanValue(false);
        }
    }

    private static void migrateLegacyLargeBarrelConfig(JsonObject root) {
        if (hasConfigEntry(root, CARPET_LARGE_BARREL_MODE.getName())) return;
        if (!hasConfigEntry(root, LEGACY_ENABLE_CARPET_LARGE_BARRELS.getName())) return;

        CARPET_LARGE_BARREL_MODE.setValueFromString(
                LEGACY_ENABLE_CARPET_LARGE_BARRELS.getBooleanValue() ? CarpetLargeBarrelMode.ON.getStringValue() : CarpetLargeBarrelMode.OFF.getStringValue()
        );
    }

    private static void migrateLegacyAutoStashConfig(JsonObject root) {
        if (hasConfigEntry(root, STORE_ORDERLY.getName())) return;
        if (!hasConfigEntry(root, AUTO_STASH_ITEMS.getName())) return;

        STORE_ORDERLY.setBooleanValue(AUTO_STASH_ITEMS.getBooleanValue());
    }

    private static void migrateLegacyHiddenGuiConfig(JsonObject root) {
        if (!hasConfigEntry(root, HIDE_FILLER_GUI.getName())) return;

        boolean legacyValue = HIDE_FILLER_GUI.getBooleanValue();
        if (!hasConfigEntry(root, HIDE_PROJECTION_FILL_GUI.getName())) {
            HIDE_PROJECTION_FILL_GUI.setBooleanValue(legacyValue);
        }
        if (!hasConfigEntry(root, HIDE_TOOL_GUI.getName())) {
            HIDE_TOOL_GUI.setBooleanValue(legacyValue);
        }
    }

    private static boolean hasConfigEntry(JsonObject root, String key) {
        if (root == null || key == null) return false;
        JsonObject features = JsonUtils.getNestedObject(root, "Features", false);
        return features != null && features.has(key);
    }

    @Override
    public void save() {
        File dir = FabricLoader.getInstance().getConfigDir().toFile();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Features", Configs.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME));
        }
    }
    public static void init() {
        Configs.INSTANCE.load();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        // Register keybinds once at init time. Doing this inside save() would mutate
        // malilib's hotkeyMap while InputEventHandler is iterating it on the key press
        // that triggered the save, throwing ConcurrentModificationException.
        InputHandler.getInstance().addKeysToMap(InputEventHandler.getKeybindManager());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        // malilib Registry.CONFIG_SCREEN not available in 1.20.1 malilib
    }

    public static void saveToFile() {
        INSTANCE.save();
        RealContainerCache.applyConfiguredCacheLimit();
        ConfigManager.getInstance().onConfigsChanged(Reference.MOD_ID);
    }

    public static int getConfiguredCacheEntryLimit() {
        return CACHE_ENTRY_LIMIT.getIntegerValue();
    }

    public static long getCacheTtlMs() {
        return CACHE_TTL.getIntegerValue() * 1000L;
    }

    public static CarpetLargeBarrelMode getCarpetLargeBarrelMode() {
        if (CARPET_LARGE_BARREL_MODE.getOptionListValue() instanceof CarpetLargeBarrelMode mode) {
            return mode;
        }
        return CarpetLargeBarrelMode.OFF;
    }
}
