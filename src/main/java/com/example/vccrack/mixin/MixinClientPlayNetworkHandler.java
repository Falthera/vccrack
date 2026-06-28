package com.example.vccrack.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.GameMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.example.vccrack.VoiceChatCrackMod;
import net.minecraft.text.Component;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler {
    @Inject(at = @At("TAIL"), method = "handleGameMessage(Lnet/minecraft/network/packet/s2c/GameMessageS2CPacket;)V")
    private void vcrack$onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        try {
            Component content = packet.content();
            if (content == null) return;
            String text = content.getString();
            if (text == null || text.isEmpty()) return;
            if (text.contains("Joined group") || text.contains("joined group") || text.contains("join_successful")) {
                VoiceChatCrackMod.signalSuccess();
            }
        } catch (Throwable t) {
            // ignore
        }
    }
}
