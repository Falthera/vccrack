package com.example.vccrack.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "de.maxhenkel.voicechat.voice.client.ClientGroupManager")
public interface MixinClientGroupManager {
    // Placeholder mixin for future client-side group state access
}
