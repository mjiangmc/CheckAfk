package newblock.checkafk;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAchievementAwardedEvent;

public class ActivityListener implements Listener {

    private final CheckAfk plugin;
    private final FileConfiguration config;

    public ActivityListener(CheckAfk plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
    }

    private void update(Player player, String key) {
        if (player.hasPermission("checkafk.bypass")) return;

        if (config.getBoolean(key)) {
            plugin.updateActivity(player);
            if (config.getBoolean("debug")) {
                Bukkit.getLogger().info(plugin.formatMessage("activity-triggered", "action", key, "player", player.getName()));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.clearActivity(e.getPlayer());
        plugin.updateActivity(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.clearActivity(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.getFrom().toVector().equals(e.getTo().toVector()))
            update(e.getPlayer(), "move");
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent e) {
        update(e.getPlayer(), "sprint");
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        update(e.getPlayer(), "shift");
    }

    @EventHandler
    public void onHotbar(PlayerItemHeldEvent e) {
        update(e.getPlayer(), "hotbar");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        update(e.getPlayer(), "breakblock");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        update(e.getPlayer(), "placeblock");
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.hasItem() && e.getItem().getType() != Material.AIR)
            update(e.getPlayer(), "useitem");
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player)
            update((Player) e.getDamager(), "attack");
    }

    @EventHandler
    public void onAchievement(PlayerAchievementAwardedEvent e) {
        update(e.getPlayer(), "achievement");
    }
}
