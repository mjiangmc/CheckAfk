package newblock.checkafk;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;

public final class CheckAfk extends JavaPlugin {

    private final HashMap<UUID, Long> lastActive = new HashMap<>();
    private FileConfiguration config;
    private FileConfiguration lang;
    private String prefix;
    private int checkerTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();
    }

    @Override
    public void onDisable() {
        lastActive.clear();
        if (checkerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkerTaskId);
        }
    }

    public void updateActivity(Player player) {
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void clearActivity(Player player) {
        lastActive.remove(player.getUniqueId());
    }

    public long getLastActive(Player player) {
        return lastActive.getOrDefault(player.getUniqueId(), System.currentTimeMillis());
    }

    public FileConfiguration getPluginConfig() {
        return config;
    }

    public FileConfiguration getLang() {
        return lang;
    }

    public String getPrefix() {
        return prefix;
    }

    public void reloadAll() {
        reloadConfig();
        config = getConfig();
        prefix = config.getString("prefix", "&a[CheckAfk]");

        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) saveResource("lang.yml", false);
        lang = YamlConfiguration.loadConfiguration(langFile);

        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(new ActivityListener(this), this);

        if (checkerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkerTaskId);
        }
        checkerTaskId = Bukkit.getScheduler().runTaskTimer(this, new AfkChecker(this), 20L, 20L).getTaskId();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("checkafk.reload")) {
                reloadAll();
                sender.sendMessage(formatMessage("reload-success"));
            } else {
                sender.sendMessage(formatMessage("no-permission"));
            }
            return true;
        }
        sender.sendMessage(formatMessage("usage"));
        return true;
    }

    public String formatMessage(String key) {
        return translateColorCodes(prefix + " " + lang.getString(key, key));
    }

    public String formatMessage(String key, String... replacements) {
        String message = lang.getString(key, key);
        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return translateColorCodes(prefix + " " + message);
    }

    private String translateColorCodes(String message) {
        return message.replaceAll("&", "§");
    }
}
