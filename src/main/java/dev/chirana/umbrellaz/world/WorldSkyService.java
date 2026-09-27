package dev.chirana.umbrellaz.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;

public final class WorldSkyService {
    private static final int WEATHER_DURATION = Integer.MAX_VALUE;

    public void apply(MinecraftServer server, WorldSkyMode mode) {
        server.setWeatherParameters(
                mode == WorldSkyMode.CLEAN ? WEATHER_DURATION : 0,
                mode == WorldSkyMode.CLEAN ? 0 : WEATHER_DURATION,
                mode != WorldSkyMode.CLEAN,
                mode == WorldSkyMode.STORM);
        for (ServerLevel world : server.getAllLevels()) {
            world.setRainLevel(mode.rainLevel());
            world.setThunderLevel(mode.thunderLevel());
            broadcastWeather(server, world, mode);
        }
    }

    private void broadcastWeather(MinecraftServer server, ServerLevel world, WorldSkyMode mode) {
        ClientboundGameEventPacket.Type rainEvent = mode == WorldSkyMode.CLEAN
                ? ClientboundGameEventPacket.STOP_RAINING
                : ClientboundGameEventPacket.START_RAINING;
        server.getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(rainEvent, 0.0F),
                world.dimension());
        server.getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, mode.rainLevel()),
                world.dimension());
        server.getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, mode.thunderLevel()),
                world.dimension());
    }
}
