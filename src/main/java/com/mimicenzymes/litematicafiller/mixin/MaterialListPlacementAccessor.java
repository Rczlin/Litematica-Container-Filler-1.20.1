package com.mimicenzymes.litematicafiller.mixin;

import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MaterialListPlacement.class, remap = false)
public interface MaterialListPlacementAccessor {
    @Accessor("placement")
    SchematicPlacement getPlacement();
}
