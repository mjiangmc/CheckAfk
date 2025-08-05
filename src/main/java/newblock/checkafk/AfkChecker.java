package newblock.checkafk;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

public class AfkChecker implements Runnable {

    private final CheckAfk plugin;
    private final FileConfiguration config;

    public AfkChecker(CheckAfk plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        List<String> commands = config.getStringList("commands");
        boolean debug = config.getBoolean("debug");

        // 检查是否在允许检测的时间段内
        if (!plugin.isTimeEnabled()) {
            if (debug) {
                Bukkit.getLogger().info(plugin.formatMessage("time-range-disabled"));
            }
            // 在禁用时间段内，重置所有玩家的活跃时间
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.setLastActive(player, now);
            }
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            // 检查是否在被禁用的世界中
            if (!plugin.isWorldEnabled(player)) {
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage(
                        "跳过世界 " + player.getWorld().getName() + " 中的玩家 " + player.getName()));
                }
                continue;
            }
            
            if (player.hasPermission("checkafk.bypass")) continue;

            long last = plugin.getLastActive(player);
            // 添加时间合理性检查
            if (last <= 0 || last > now) {
                // 异常时间值，重置为当前时间
                plugin.setLastActive(player, now);
                last = now;
            }
            long elapsed = now - last;
            int playerTimeout = plugin.getPlayerTime(player.getUniqueId()) * 1000;
            
            // 最大合理AFK时间设为1小时(3600000毫秒)
            long maxReasonableAfkTime = 3600000;
            if (elapsed > maxReasonableAfkTime) {
                // 超过最大合理时间，重置活动时间
                plugin.setLastActive(player, now);
                elapsed = 0;
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage("reset-abnormal-time", 
                        "player", player.getName(),
                        "elapsed", String.valueOf(elapsed / 1000)));
                }
                continue;
            }

            if (debug) {
                Bukkit.getLogger().info(plugin.formatMessage("checking-player", 
                    "player", player.getName(), 
                    "seconds", String.valueOf(elapsed / 1000),
                    "timeout", String.valueOf(playerTimeout / 1000)));
            }

            if (elapsed >= playerTimeout) {
                for (String cmd : commands) {
                    String run = cmd.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);

                    if (debug) {
                        Bukkit.getLogger().info(plugin.formatMessage("executing-command", "command", run));
                    }
                }
                plugin.clearActivity(player); // 清除记录，玩家需要重新活动
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage("cleared-activity", "player", player.getName()));
                }
            }
        }
    }
}
