package newblock.checkafk;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class CheckAfkExpansion extends PlaceholderExpansion {

    private final CheckAfk plugin;

    public CheckAfkExpansion(CheckAfk plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "checkafk";
    }

    @Override
    public @NotNull String getAuthor() {
        return "NewBlock";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        if (params.equalsIgnoreCase("time")) {
            return String.valueOf(plugin.getPlayerTime(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("status")) {
            long lastActive = plugin.getLastActive(player);
            long now = System.currentTimeMillis();
            long timeout = plugin.getPlayerTime(player.getUniqueId()) * 1000L;
            return (now - lastActive) >= timeout ? "AFK" : "Active";
        }

        if (params.equalsIgnoreCase("execcommandsserver")) {
            return plugin.getAfkServer(player.getUniqueId());
        }

        return null;
    }

    @Override
    public boolean persist() {
        return true;
    }
}