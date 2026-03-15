package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RealContainerCache {
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> LOCK_CACHE = new ConcurrentHashMap<>();
    private static BlockPos lastLookedPos = null;

    private static final Map<BlockPos, Map<Integer, ItemStack>> NBT_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, BlockPos> PENDING_NBT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_REQUEST_TIME = new ConcurrentHashMap<>();
    private static int transactionCounter = 10000;

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        if (client.currentScreen == null && client.crosshairTarget instanceof BlockHitResult bhr) {
            lastLookedPos = bhr.getBlockPos();
        }

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            updateFromScreen(client, screen);
        }
    }

    public static void updateFromScreen(MinecraftClient client, HandledScreen<?> screen) {
        BlockPos pos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        if (pos == null) pos = lastLookedPos;
        if (pos == null) return;

        Map<Integer, ItemStack> items = new HashMap<>();
        ScreenHandler handler = screen.getScreenHandler();

        for (Slot slot : handler.slots) {
            if (slot.inventory != null && slot.inventory != client.player.getInventory()) {
                if (handler instanceof net.minecraft.screen.CrafterScreenHandler && slot.getIndex() == 9) {
                    continue;
                }
                if (!slot.getStack().isEmpty()) items.put(slot.getIndex(), slot.getStack().copy());
            }
        }

        BlockState state = client.world.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);

        if (halves != null) {
            CACHE.put(halves[0].toImmutable(), items);
            CACHE.put(halves[1].toImmutable(), items);
        } else {
            CACHE.put(pos.toImmutable(), items);
        }

        if (handler instanceof net.minecraft.screen.CrafterScreenHandler crafterHandler) {
            Set<Integer> locks = new HashSet<>();
            for (int i = 0; i < 9; i++) {
                if (crafterHandler.isSlotDisabled(i)) locks.add(i);
            }
            LOCK_CACHE.put(pos.toImmutable(), locks);
        }
    }

    public static Map<Integer, ItemStack> getCachedItems(BlockPos pos) {
        if (CACHE.containsKey(pos)) {
            return CACHE.get(pos);
        }
        if (NBT_QUERY_CACHE.containsKey(pos)) {
            return NBT_QUERY_CACHE.get(pos);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            BlockState state = client.world.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);

            if (client.isInSingleplayer() && client.getServer() != null) {
                ServerWorld serverWorld = client.getServer().getWorld(client.world.getRegistryKey());
                if (serverWorld != null) {
                    return getSingleplayerRealItems(pos, serverWorld, halves, state);
                }
            }
            else if (Configs.ENABLE_DATA_SYNC.getBooleanValue()) {
                if (halves != null) {
                    Map<Integer, ItemStack> rightHalf = getServuxBlockEntityItems(client.world, halves[0]);
                    Map<Integer, ItemStack> leftHalf = getServuxBlockEntityItems(client.world, halves[1]);

                    if (rightHalf != null && leftHalf != null) {
                        Map<Integer, ItemStack> combined = new HashMap<>(rightHalf);
                        leftHalf.forEach((slot, stack) -> combined.put(slot + 27, stack));
                        return combined;
                    } else {
                        if (rightHalf == null) requestContainerData(halves[0]);
                        if (leftHalf == null) requestContainerData(halves[1]);
                    }
                } else {
                    Map<Integer, ItemStack> items = getServuxBlockEntityItems(client.world, pos);
                    if (items != null) return items;
                    requestContainerData(pos);
                }
            }
        }
        return null;
    }

    private static Map<Integer, ItemStack> getSingleplayerRealItems(BlockPos pos, ServerWorld serverWorld, BlockPos[] halves, BlockState state) {

        if (halves != null) {

            Map<Integer, ItemStack> rightHalf = getHalfChestItems(halves[0], serverWorld);
            Map<Integer, ItemStack> leftHalf = getHalfChestItems(halves[1], serverWorld);

            if (rightHalf != null && leftHalf != null) {
                Map<Integer, ItemStack> combined = new HashMap<>();

                rightHalf.forEach((slot, stack) -> combined.put(slot, stack));
                leftHalf.forEach((slot, stack) -> combined.put(slot + 27, stack));

                return combined;
            }

            return null;
        }

        return getHalfChestItems(pos, serverWorld);
    }

    private static Map<Integer, ItemStack> getHalfChestItems(BlockPos pos, ServerWorld serverWorld) {

        BlockEntity be = serverWorld.getBlockEntity(pos);

        if (be == null) {
            return null;
        }

        if (be instanceof Inventory inv) {

            Map<Integer, ItemStack> items = new HashMap<>();

            for (int i = 0; i < inv.size(); i++) {

                ItemStack stack = inv.getStack(i);

                if (stack != null && !stack.isEmpty()) {
                    items.put(i, stack.copy());
                }
            }

            return items;
        }

        NbtCompound nbt = be.createNbt(serverWorld.getRegistryManager());

        if (nbt != null && nbt.contains("Items")) {
            return parseNbtInventory(nbt, serverWorld.getRegistryManager());
        }

        return null;
    }

    private static Map<Integer, ItemStack> getServuxBlockEntityItems(net.minecraft.world.World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be != null) {
            NbtCompound nbt = be.createNbt(world.getRegistryManager());
            if (nbt != null && nbt.contains("Items")) {
                return parseNbtInventory(nbt, world.getRegistryManager());
            }
        }
        return null;
    }

    private static void requestContainerData(BlockPos pos) {
        if (!Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        if (!client.player.hasPermissionLevel(2)) return;

        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST_TIME.getOrDefault(pos, 0L) < 5000) {
            return;
        }

        int id = transactionCounter++;
        PENDING_NBT_REQUESTS.put(id, pos);
        LAST_REQUEST_TIME.put(pos, now);

        client.getNetworkHandler().sendPacket(new QueryBlockNbtC2SPacket(id, pos));
    }

    public static void handleNbtResponse(int transactionId, NbtCompound nbt) {
        BlockPos pos = PENDING_NBT_REQUESTS.remove(transactionId);
        if (pos != null && nbt != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null) {
                Map<Integer, ItemStack> items = new HashMap<>();
                if (nbt.contains("Items")) {
                    items = parseNbtInventory(nbt, client.world.getRegistryManager());
                }
                NBT_QUERY_CACHE.put(pos.toImmutable(), items);

                // 核心修复：截获并同步合成器的锁定状态
                if (nbt.contains("disabled_slots")) {
                    LOCK_CACHE.put(pos.toImmutable(), parseDisabledSlots(nbt));
                }
            }
        }
    }

    public static Set<Integer> getCachedLocks(BlockPos pos) { return LOCK_CACHE.get(pos); }

    public static void putLock(BlockPos pos, Set<Integer> locks) {
        if (pos == null || locks == null) return;
        LOCK_CACHE.put(pos.toImmutable(), locks);
    }

    public static boolean isSatisfied(BlockPos pos, Map<Integer, ItemStack> required) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        BlockState state = client.world.getBlockState(pos);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            return false;
        }

        Map<Integer, ItemStack> realItems = getCachedItems(pos);
        if (realItems != null) {
            return checkMapStrict(realItems, required, isCrafter);
        }
        return false;
    }

    private static boolean checkMapStrict(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter) {
        if (realItems == null) return false;
        int maxSlot = isCrafter ? 9 : 54;

        for (int i = 0; i < maxSlot; i++) {
            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;
            if (real.isEmpty() && req.isEmpty()) continue;
            if (real.isEmpty() != req.isEmpty() || !ItemMatcher.isSameItem(real, req) || real.getCount() != req.getCount()) {
                return false;
            }
        }
        return true;
    }

    public static Map<Integer, ItemStack> parseNbtInventory(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        NbtElement itemsElem = nbt.get("Items");
        if (itemsElem instanceof NbtList list) {
            for (int i = 0; i < list.size(); i++) {
                NbtElement itemElem = list.get(i);
                if (itemElem instanceof NbtCompound itemTag) {
                    int slot = 0;
                    if (itemTag.contains("Slot")) {
                        try { slot = Integer.parseInt(itemTag.get("Slot").toString().replaceAll("[^0-9]", "")) & 255; } catch (Exception ignored) {}
                    }

                    ItemStack stack = ItemStack.EMPTY;
                    try {
                        stack = ItemStack.OPTIONAL_CODEC.parse(registries.getOps(NbtOps.INSTANCE), itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                    } catch (Exception ignored) {}

                    if (stack.isEmpty() && itemTag.contains("id")) {
                        String idStr = itemTag.get("id").toString().replace("\"", "");
                        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(idStr);
                        if (id != null) {
                            net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
                            if (item != null && item != net.minecraft.item.Items.AIR) {
                                int count = 1;
                                try {
                                    if (itemTag.contains("Count")) count = Integer.parseInt(itemTag.get("Count").toString().replaceAll("[^0-9]", ""));
                                    else if (itemTag.contains("count")) count = Integer.parseInt(itemTag.get("count").toString().replaceAll("[^0-9]", ""));
                                } catch (Exception ignored) {}
                                stack = new ItemStack(item, count);
                            }
                        }
                    }

                    if (!stack.isEmpty()) items.put(slot, stack);
                }
            }
        }
        return items;
    }

    public static Set<Integer> parseDisabledSlots(NbtCompound nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            NbtElement elem = nbt.get("disabled_slots");
            if (elem instanceof NbtList list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.AbstractNbtNumber num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.NbtIntArray intArray) {
                for (int val : intArray.getIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static void clear() {
        CACHE.clear();
        LOCK_CACHE.clear();
        NBT_QUERY_CACHE.clear();
        PENDING_NBT_REQUESTS.clear();
        LAST_REQUEST_TIME.clear();
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;
        CACHE.put(pos.toImmutable(), items);
    }

}