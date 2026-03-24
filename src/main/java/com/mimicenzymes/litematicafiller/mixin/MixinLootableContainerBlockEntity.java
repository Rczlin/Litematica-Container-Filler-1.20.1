package com.mimicenzymes.litematicafiller.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootableContainerBlockEntity.class)
public class MixinLootableContainerBlockEntity {

    @Inject(method = "getStack", at = @At("RETURN"), cancellable = true)
    private void onGetStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        net.minecraft.world.World world = ((net.minecraft.block.entity.BlockEntity) (Object) this).getWorld();
        if (world != null && world.isClient() && world.getClass().getSimpleName().contains("Schematic")) {
            ItemStack replaced = com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceSingleStack(cir.getReturnValue());
            cir.setReturnValue(replaced);
        }
    }
}