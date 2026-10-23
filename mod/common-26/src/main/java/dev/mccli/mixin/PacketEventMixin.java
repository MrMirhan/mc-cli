package dev.mccli.mixin;

import dev.mccli.events.EventLog;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts packets the server sends this client (the integrated server's own connections are skipped). */
@Mixin(Connection.class)
public abstract class PacketEventMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void mccli$packetArrived(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (EventLog.packets() != EventLog.Packets.OFF && packet.type().flow() == PacketFlow.CLIENTBOUND) {
            EventLog.packet(packet.type().id().toString());
        }
    }
}
