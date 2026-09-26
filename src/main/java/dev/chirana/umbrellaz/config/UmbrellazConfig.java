package dev.chirana.umbrellaz.config;

public record UmbrellazConfig(RestrictedSpawn restrictedSpawn) {
    public record RestrictedSpawn(String world, double x, double y, double z, float yaw, float pitch) {
    }
}
