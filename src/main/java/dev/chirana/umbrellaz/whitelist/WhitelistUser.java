package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.player.Player;

public record WhitelistUser(Player player, boolean whitelisted) {
}
