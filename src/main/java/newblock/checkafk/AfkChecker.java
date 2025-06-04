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
        int timeout = config.getInt("time") * 1000;
        List<String> commands = config.getStringList("commands");
        boolean debug = config.getBoolean("debug");

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("checkafk.bypass")) continue;

            long last = plugin.getLastActive(player);
            long elapsed = now - last;

            if (debug) {
                Bukkit.getLogger().info(plugin.formatMessage("checking-player", "player", player.getName(), "seconds", String.valueOf(elapsed / 1000)));
            }

            if (elapsed >= timeout) {
                for (String cmd : commands) {
                    String run = cmd.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);

                    if (debug) {
                        Bukkit.getLogger().info(plugin.formatMessage("executing-command", "command", run));
                    }
                }
                plugin.updateActivity(player); // 重置时间，避免重复执行
            }
        }
    }
}
