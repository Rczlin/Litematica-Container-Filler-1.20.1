# Litematica Container Filler (1.20.1)

A Minecraft 1.20.1 Fabric port of [Litematica Container Filler](https://github.com/MimicEnzymes/Litematica-Container-Filler) with **performance optimizations** and **stability improvements**.

---

## What's Different from the Original

This is a backport of the original mod to **Minecraft 1.20.1 Fabric**. Key improvements over the upstream:

- **Rendering overhaul** — Replaced the VertexBuffer-based cache architecture with immediate-mode rendering, fixing 3D camera rotation issues on 1.20.1
- **Split-frame highlight scanning** — Highlight scanning is now spread across multiple frames (HighlightScanPass) to eliminate single-frame performance spikes
- **Highlight render cache** — Introduced a render cache to reduce redundant draw calls; highlight budget is configurable
- **Async PCA queue** — PCA sync and region scanning are now fully async; removed MiniHUD reflection cache for better responsiveness
- **Configurable cache TTL** — Cache capacity and time-to-live are now user-configurable, with a GUI cache status monitor
- **Accurate item routing** — Improved item transport path calculation for container filling
- **Pre-fill block validation** — Validates the real block state before filling to prevent mismatches
- **UI timeout diagnostics** — Automatic diagnostics when container data requests time out, making troubleshooting easier
- **Configurable interaction distance** — Adjust player interaction range to suit different server environments
- **Categorized debug logging** — Structured debug log system with performance hotspot timing
- **Queued PCA requests** — PCA requests are now queued with timeout and retry cooldown for improved stability
- **PCA compatibility wrapper** — PCA receiver is wrapped for compatibility with other mods using the PCA protocol

---

## Main Features

### Schematic Container Filling

- Fills real containers according to the container NBT and slot layout stored in the Litematica schematic.
- **One-shot filling**: look at a target container and press the fill hotkey.
- **Work State**: continuously scans nearby containers, queues valid tasks, and fills them automatically.
- **Crafter slot locking**: even if materials are missing, the mod still prioritizes required slot locks.
- Handles double chests as one connected container.
- Supports Carpet `largeBarrels` (must be enabled manually in config).
- Configurable fill radius, action delay, safety delay, and state protection.

### Container State Highlights

Renders container status directly in the 3D world:

- Unfilled / Partial / Satisfied / Overfilled
- Wrong item / Unknown data / Unplaced container
- Manually completed / Manually needs fill

The renderer uses translucent bodies, top plates, and animated 3D task markers. Containers being filled, queued containers, and missing-material events each have separate animated markers.

Highlight colors, opacity, task markers, animation FPS limits, render radius, and per-state toggles are all configurable. X-ray highlights are disabled by default.

### Item Replacement

Item replacement affects actual filling behavior, not only the material list display:

- Supports global replacement rules and per-schematic rules (per-schematic takes priority).
- Visual replacement picker.
- Supports exact item data: custom names, enchantments, and data components.
- Format: `minecraft:stone->minecraft:cobblestone`
- Custom-name matching: `minecraft:paper#slot1->minecraft:iron_ingot`
- Replacing an item with `minecraft:air` makes the filler ignore that item.

### Material List Integration

- Show only building blocks / only container items / both together.
- Open the replacement picker directly from the material list.
- Replacement markers and tooltips for replaced items.
- Collect missing material-list items from the container you are looking at.

### Inventory & Shulker Logistics

- Extracts required items from shulker boxes in the player inventory.
- Supports QuickShulker.
- Supports the TakeItOut server-side protocol.
- Shulker boxes with different colors are treated as compatible if their contents match.
- Orderly item storage: items are returned to source shulkers or matching shulkers first when space is needed.
- Slow click packet mode for plugin-heavy or stricter anti-cheat servers.

### Container Tools

Independent look-at-container tools:

- **Clear Container** — clears the target container. Output can be dropped, moved to inventory, or both.
- **Fill Full** — fills the target container with an unambiguous item from your inventory.
- **Sync Container** — reads a template container and syncs other targets to the same layout.
- **Container Packing** — packs items from the target container into shulker boxes in your inventory.
- **Collect Materials** — collects missing Litematica material-list items from the target container.

Tools support next/previous switching, closing all tools, hold-to-repeat, same-container cooldowns, and an optional tool HUD.

### Container Filter

- Disabled / Whitelist / Blacklist modes.
- Apply the filter to schematic filling, container tools, or both.
- Visual container picker.
- Wildcard rules: `minecraft:*_shulker_box`
- Auto-detects modded containers via block entities, inventory interfaces, and common container-like block IDs.

### Manual Container Marks

Manually cycle container state:

- Automatic / Manually completed / Manually needs fill

Works on containers outside the current schematic range.

---

## Usage

- Default config hotkey: `L + C`
- Default one-shot fill hotkey: `V`
- For continuous filling, enable **Work State** in the config, bind a hotkey, and adjust the fill radius and action delay.

---

## Dependencies

**Required:**

- Fabric Loader
- Fabric API
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

**Optional:**

- Mod Menu — config screen access
- QuickShulker — fast inventory shulker opening and extraction
- [PCA](https://modrinth.com/mod/pca) — fetch real container data without opening containers
- MiniHUD — the mod may read its container preview cache
- TakeItOut — server-side shulker extraction support

---

## Notes & Warnings

**Server risk:** Automated container operations may be flagged by server plugins or anti-cheat systems as abnormal clicking or auto-sorting. Increase the action delay or enable slow click packet mode if needed.

**Empty-schematic container clearing:** If enabled, the mod may remove items from real containers that are empty in the schematic. Verify your schematic and range before enabling.

**Carpet large barrels:** Only enable this if the server actually runs Carpet with `largeBarrels` enabled.

---

## License

This project is licensed under `LGPL-3.0-only`.
