package com.sunlitvalley.menucards.network;

import com.sunlitvalley.menucards.MenuCardsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(MenuCardsMod.MOD_ID, "main"))
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel();

    private static int packetId = 0;

    public static void register() {
        CHANNEL.messageBuilder(CardActionC2SPacket.class, packetId++)
            .encoder(CardActionC2SPacket::encode)
            .decoder(CardActionC2SPacket::decode)
            .consumerMainThread(CardActionC2SPacket::handle)
            .add();
    }

    public static void sendToServer(CardActionC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
