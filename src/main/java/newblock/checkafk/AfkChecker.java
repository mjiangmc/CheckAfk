package newblock.checkafk;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
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

        // 如果服务器名未获取且有玩家在线，尝试获取
        if (plugin.getServerName() == null) {
            Player anyPlayer = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                anyPlayer = p;
                break;
            }
            if (anyPlayer != null) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("GetServer");
                anyPlayer.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage("requesting-server-name"));
                }
            } else if (debug) {
                Bukkit.getLogger().warning("无在线玩家，无法请求服务器名称");
            }
        }

        // 检查是否在允许检测的时间段内
        if (!plugin.isTimeEnabled()) {
            if (debug) {
                Bukkit.getLogger().info(plugin.formatMessage("time-range-disabled"));
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.setLastActive(player, now);
            }
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("checkafk.bypass")) continue;

            if (!plugin.isWorldEnabled(player)) {
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage("world-disabled",
                            "player", player.getName(),
                            "world", player.getWorld().getName()));
                }
                plugin.setLastActive(player, now);
                continue;
            }

            long last = plugin.getLastActive(player);
            if (last <= 0 || last > now) {
                plugin.setLastActive(player, now);
                last = now;
            }
            long elapsed = now - last;
            int playerTimeout = plugin.getPlayerTime(player.getUniqueId()) * 1000;

            long maxReasonableAfkTime = 3600000;
            if (elapsed > maxReasonableAfkTime) {
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
                if (config.getBoolean("mysql.enabled")) {
                    String serverName = plugin.getServerName();
                    if (serverName != null && !serverName.isEmpty()) {
                        plugin.recordAfkServer(player.getUniqueId(), serverName);
                        if (debug) {
                            Bukkit.getLogger().info("玩家进入AFK状态，记录服务器: 玩家=" + player.getName() + ", Server=" + serverName);
                        }
                    } else if (debug) {
                        Bukkit.getLogger().warning(plugin.formatMessage("no-server-name",
                                "player", player.getName()));
                    }
                }
                for (String cmd : commands) {
                    String run = cmd.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
                    if (debug) {
                        Bukkit.getLogger().info(plugin.formatMessage("executing-command", "command", run));
                    }
                }
                plugin.clearActivity(player);
                if (debug) {
                    Bukkit.getLogger().info(plugin.formatMessage("cleared-activity", "player", player.getName()));
                }
            }
        }
    }
}