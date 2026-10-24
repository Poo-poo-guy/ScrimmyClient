package com.scrimmy.client.mixin;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.visual.BlockESPModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void scrimmy$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> ci) {
        LevelChunk chunk = (LevelChunk)(Object)this;
        if (ci.getReturnValue() == null || !(chunk.getLevel() instanceof ClientLevel level)) return;
        BlockESPModule module = ScrimmyClient.MODULES.find(BlockESPModule.class);
        if (module != null && module.enabled()) module.cache.changed(level, pos, chunk.getBlockState(pos));
    }
}
