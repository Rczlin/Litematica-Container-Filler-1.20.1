package com.mimicenzymes.litematicafiller.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public class MixinBlockEntity {

    @Inject(method = {
            "createNbt",
            "createNbtWithIdentifyingData"
    }, at = @At("RETURN"))
    private void onSerializeNbtAny(RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<NbtCompound> cir) {
        net.minecraft.world.World world = ((BlockEntity) (Object) this).getWorld();
        if (world != null && world.isClient() && world.getClass().getSimpleName().contains("Schematic")) {
            NbtCompound nbt = cir.getReturnValue();
            if (nbt != null && nbt.contains("Items")) {
                com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceInNbtList((NbtList) nbt.get("Items"), registries);
            }
        }
    }
}