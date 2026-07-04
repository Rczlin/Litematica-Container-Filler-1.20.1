package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class LitematicaCache
{
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new HashMap<>();
    private static final Map<BlockPos, Long> CACHE_TIME = new HashMap<>();

    public static void clear()
    {
        CACHE.clear();
        CACHE_TIME.clear();
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items)
    {
        BlockPos key = pos.toImmutable();
        CACHE.put(key, items);
        CACHE_TIME.put(key, System.currentTimeMillis());
    }

    public static Map<Integer, ItemStack> get(BlockPos pos)
    {
        Long seenAt = CACHE_TIME.get(pos);
        if (seenAt == null)
        {
            return CACHE.get(pos);
        }

        if (System.currentTimeMillis() - seenAt > Configs.getCacheTtlMs())
        {
            CACHE.remove(pos);
            CACHE_TIME.remove(pos);
            return null;
        }

        return CACHE.get(pos);
    }

    public static void cleanupExpired()
    {
        long now = System.currentTimeMillis();
        long ttl = Configs.getCacheTtlMs();
        CACHE_TIME.entrySet().removeIf(entry -> now - entry.getValue() > ttl);
        CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
    }
}
