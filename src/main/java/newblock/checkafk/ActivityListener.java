package newblock.checkafk;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityListener implements Listener {

    private final CheckAfk plugin;
    private final FileConfiguration config;

    // 新增：记录每个玩家上一次有效的视角（只在转动 >30° 时更新）
    private final ConcurrentHashMap<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastPitch = new ConcurrentHashMap<>();

    public ActivityListener(CheckAfk plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
    }

    private void update(Player player, String key) {
        if (player.hasPermission("checkafk.bypass")) return;

        // 检查玩家所在世界是否启用AFK检测
        if (!plugin.isWorldEnabled(player)) {
            if (config.getBoolean("debug")) {
                Bukkit.getLogger().info(plugin.formatMessage("world-disabled",
                        "player", player.getName(),
                        "world", player.getWorld().getName()));
            }
            return;
        }

        if (config.getBoolean(key, true)) { // 默认开启
            plugin.updateActivity(player);
            if (config.getBoolean("debug")) {
                Bukkit.getLogger().info(plugin.formatMessage("activity-triggered", "action", key, "player", player.getName()));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        plugin.clearActivity(player);
        plugin.updateActivity(player);
        plugin.playerTimes.remove(player.getUniqueId());
        plugin.getPlayerTime(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        plugin.clearActivity(player);
        // 清理视角缓存，防止内存泄漏
        UUID uuid = player.getUniqueId();
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent e) {
        if (e.isSprinting()) {
            update(e.getPlayer(), "sprint");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) {
            update(e.getPlayer(), "shift");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent e) {
        update(e.getPlayer(), "hotbar");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        update(e.getPlayer(), "breakblock");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        update(e.getPlayer(), "placeblock");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {
        if (e.hasItem() && e.getItem().getType() != Material.AIR) {
            update(e.getPlayer(), "useitem");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player) {
            update((Player) e.getDamager(), "attack");
        }
    }

    // 视角转动检测（>30° 才算活跃）
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLookAround(PlayerMoveEvent e) {
        Player player = e.getPlayer();

        if (player.hasPermission("checkafk.bypass")) return;
        if (!plugin.isWorldEnabled(player)) return;

        // 配置开关，默认开启
        if (!config.getBoolean("look-around", true)) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // 完全忽略位置移动，只检测视角变化
        float yawFrom = from.getYaw();
        float yawTo = to.getYaw();
        float pitchFrom = from.getPitch();
        float pitchTo = to.getPitch();

        if (yawFrom == yawTo && pitchFrom == pitchTo) return;

        UUID uuid = player.getUniqueId();
        Float recordedYaw = lastYaw.get(uuid);
        Float recordedPitch = lastPitch.get(uuid);

        boolean shouldUpdate = false;

        if (recordedYaw == null || recordedPitch == null) {
            // 首次记录，直接算活跃
            shouldUpdate = true;
        } else {
            // 计算角度差（考虑 360° 循环）
            float yawDiff = Math.abs(normalizeAngle(yawTo - recordedYaw));
            float pitchDiff = Math.abs(pitchTo - recordedPitch);

            // 任意方向转动超过 30° 就算活跃
            if (yawDiff > 30.0f || pitchDiff > 30.0f) {
                shouldUpdate = true;
            }
        }

        if (shouldUpdate) {
            plugin.updateActivity(player);

            if (config.getBoolean("debug")) {
                Bukkit.getLogger().info(plugin.formatMessage("activity-triggered",
                        "action", "look-around (>30°)", "player", player.getName()));
            }

            // 更新记录的视角
            lastYaw.put(uuid, yawTo);
            lastPitch.put(uuid, pitchTo);
        }
    }

    // 角度标准化：将角度规范到 -180 ~ 180
    private float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }
}