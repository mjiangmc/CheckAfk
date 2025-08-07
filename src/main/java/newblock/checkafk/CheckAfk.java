package newblock.checkafk;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class CheckAfk extends JavaPlugin {

    private final ConcurrentHashMap<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> playerTimes = new ConcurrentHashMap<>();
    private FileConfiguration config;
    private FileConfiguration lang;
    private FileConfiguration data;
    private String prefix;
    private int checkerTaskId = -1;
    private HikariDataSource dataSource;

    @Override
    public void onEnable() {
        // 保存默认配置并检查版本
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        getConfig().addDefault("configver", 2);
        if (getConfig().getInt("configver", 1) < 2) {
            getLogger().info("检测到旧版配置文件，正在更新...");
            saveResource("config.yml", true); // 强制覆盖
            reloadConfig();
        }
        reloadAll();
    }

    @Override
    public void onDisable() {
        lastActive.clear();
        if (checkerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkerTaskId);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public void updateActivity(Player player) {
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void clearActivity(Player player) {
        lastActive.remove(player.getUniqueId());
    }

    public long getLastActive(Player player) {
        UUID uuid = player.getUniqueId();
        // 如果玩家没有记录，先记录当前时间
        if (!lastActive.containsKey(uuid)) {
            lastActive.put(uuid, System.currentTimeMillis());
        }
        return lastActive.get(uuid);
    }

    public void setLastActive(Player player, long time) {
        lastActive.put(player.getUniqueId(), time);
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

        // 初始化数据文件
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            saveResource("data.yml", false);
            getLogger().info("已创建data.yml数据文件");
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        // 初始化MySQL
        if (dataSource != null) {
            dataSource.close();
        }
        if (config.getBoolean("mysql.enabled", false)) {
            setupDatabase();
            loadPlayerTimes(); // 加载所有玩家的自定义时间
        }

        // 初始化PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new CheckAfkExpansion(this).register();
        }

        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(new ActivityListener(this), this);

        if (checkerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkerTaskId);
        }
        checkerTaskId = Bukkit.getScheduler().runTaskTimer(this, new AfkChecker(this), 20L, 20L).getTaskId();
    }

    private void setupDatabase() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + 
            config.getString("mysql.host") + ":" + 
            config.getInt("mysql.port") + "/" + 
            config.getString("mysql.database") + 
            "?useSSL=" + config.getBoolean("mysql.useSSL", false));
        hikariConfig.setUsername(config.getString("mysql.username"));
        hikariConfig.setPassword(config.getString("mysql.password"));
        hikariConfig.setPoolName("CheckAFK-Pool");
        
        // 连接池配置
        hikariConfig.setMaximumPoolSize(config.getInt("mysql.connection_pool.maximum-pool-size", 10));
        hikariConfig.setMinimumIdle(config.getInt("mysql.connection_pool.minimum-idle", 5));
        hikariConfig.setConnectionTimeout(config.getLong("mysql.connection_pool.connection-timeout", 30000));
        hikariConfig.setIdleTimeout(config.getLong("mysql.connection_pool.idle-timeout", 600000));
        hikariConfig.setMaxLifetime(config.getLong("mysql.connection_pool.max-lifetime", 1800000));
        
        dataSource = new HikariDataSource(hikariConfig);
        
        try (Connection connection = dataSource.getConnection()) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            connection.createStatement().executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_times (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "afk_time INT NOT NULL DEFAULT 0)");
        } catch (SQLException e) {
            getLogger().severe("无法初始化数据库: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 支持两种命令格式: /checkafk 和 /afkcheck
        if (!command.getName().equalsIgnoreCase("checkafk") && !command.getName().equalsIgnoreCase("afkcheck")) {
            return false;
        }
        
        if (args.length == 0) {
            sender.sendMessage(formatMessage("usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (sender.hasPermission("checkafk.reload")) {
                    reloadAll();
                    sender.sendMessage(formatMessage("reload-success"));
                } else {
                    sender.sendMessage(formatMessage("no-permission"));
                }
                return true;
                
            case "time":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(formatMessage("player-only"));
                    return true;
                }
                if (!sender.hasPermission("checkafk.time")) {
                    sender.sendMessage(formatMessage("no-permission"));
                    return true;
                }
                if (args.length != 2) {
                    sender.sendMessage(formatMessage("time-usage"));
                    return true;
                }
                try {
                    int minutes = Integer.parseInt(args[1]);
                    if (minutes <= 0) {
                        sender.sendMessage(formatMessage("invalid-time"));
                        return true;
                    }
                    Player player = (Player) sender;
                    savePlayerTime(player.getUniqueId(), minutes);
                    sender.sendMessage(formatMessage("time-set", "minutes", String.valueOf(minutes)));
                } catch (NumberFormatException e) {
                    sender.sendMessage(formatMessage("invalid-number"));
                }
                return true;
                
            case "reset":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(formatMessage("player-only"));
                    return true;
                }
                if (!sender.hasPermission("checkafk.reset")) {
                    sender.sendMessage(formatMessage("no-permission"));
                    return true;
                }
                Player player = (Player) sender;
                resetPlayerTime(player.getUniqueId());
                sender.sendMessage(formatMessage("time-reset"));
                return true;
                
            default:
                sender.sendMessage(formatMessage("usage"));
                return true;
        }
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

    public boolean isWorldEnabled(Player player) {
        List<String> disabledWorlds = config.getStringList("disabled-worlds");
        return !disabledWorlds.contains(player.getWorld().getName());
    }

    public boolean isTimeEnabled() {
        List<String> timeRanges = config.getStringList("enable-time-range");
        if (timeRanges.isEmpty()) {
            return true; // 没有配置时间限制，默认启用
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        String currentTime = sdf.format(new Date());

        for (String range : timeRanges) {
            String[] parts = range.split("-");
            if (parts.length != 2) continue;

            try {
                String startTime = parts[0].trim();
                String endTime = parts[1].trim();
                
                // 如果当前时间在任何一个禁用时间段内，返回false
                if (currentTime.compareTo(startTime) >= 0 && 
                    currentTime.compareTo(endTime) <= 0) {
                    return false;
                }
            } catch (Exception e) {
                getLogger().warning("无效的时间范围格式: " + range);
            }
        }
        return true;
    }

    public int getPlayerTime(UUID uuid) {
        if (playerTimes.containsKey(uuid)) {
            return playerTimes.get(uuid);
        }
        
        if (config.getBoolean("mysql.enabled", false)) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "SELECT afk_time FROM " + tablePrefix + "player_times WHERE uuid = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    int time = rs.getInt("afk_time");
                    playerTimes.put(uuid, time);
                    return time;
                }
            } catch (SQLException e) {
                getLogger().warning("获取玩家时间失败: " + e.getMessage());
            }
        } else {
            // 从data.yml读取
            if (data.contains("players." + uuid.toString())) {
                int time = data.getInt("players." + uuid.toString());
                playerTimes.put(uuid, time);
                return time;
            }
        }
        
        // 优先使用time配置，兼容default-afk-time配置，最后使用默认值
        return config.getInt("time", config.getInt("default-afk-time", 300));
    }

    private void savePlayerTime(UUID uuid, int minutes) {
        playerTimes.put(uuid, minutes);
        
        if (config.getBoolean("mysql.enabled", false)) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "INSERT INTO " + tablePrefix + "player_times (uuid, afk_time) " +
                         "VALUES (?, ?) ON DUPLICATE KEY UPDATE afk_time = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setInt(2, minutes);
                stmt.setInt(3, minutes);
                stmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("保存玩家时间失败: " + e.getMessage());
            }
         } else {
            // 保存到data.yml
            data.set("players." + uuid.toString(), minutes);
            try {
                data.save(new File(getDataFolder(), "data.yml"));
            } catch (IOException e) {
                getLogger().warning("保存玩家时间到data.yml失败: " + e.getMessage());
            }
        }
    }

    private void resetPlayerTime(UUID uuid) {
        playerTimes.remove(uuid);
        
        if (dataSource != null) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "DELETE FROM " + tablePrefix + "player_times WHERE uuid = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("重置玩家时间失败: " + e.getMessage());
            }
        }
    }

    private void loadPlayerTimes() {
        if (dataSource != null) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "SELECT uuid, afk_time FROM " + tablePrefix + "player_times";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    int time = rs.getInt("afk_time");
                    playerTimes.put(uuid, time);
                }
            } catch (SQLException e) {
                getLogger().warning("加载玩家时间失败: " + e.getMessage());
            }
        }
    }
}
