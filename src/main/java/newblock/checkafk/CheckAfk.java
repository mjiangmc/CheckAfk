package newblock.checkafk;

import com.google.common.io.ByteStreams;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class CheckAfk extends JavaPlugin {

    public final ConcurrentHashMap<UUID, Long> lastActive = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, Integer> playerTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> afkServers = new ConcurrentHashMap<>();
    private FileConfiguration config;
    private FileConfiguration lang;
    private FileConfiguration data;
    private String prefix;
    private int checkerTaskId = -1;
    private HikariDataSource dataSource;
    private String serverName = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        getConfig().addDefault("configver", 2);
        if (getConfig().getInt("configver", 1) < 2) {
            getLogger().info("检测到旧版配置文件，正在更新...");
            saveResource("config.yml", true);
            reloadConfig();
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", new BungeeListener(this));

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
        // 不再每次活动时记录服务器名称，仅在 AFK 时记录（由 AfkChecker 调用）
    }

    public void clearActivity(Player player) {
        lastActive.remove(player.getUniqueId());
    }

    public long getLastActive(Player player) {
        UUID uuid = player.getUniqueId();
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

        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            saveResource("data.yml", false);
            getLogger().info("已创建data.yml数据文件");
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        if (dataSource != null) {
            dataSource.close();
        }
        if (config.getBoolean("mysql.enabled", false)) {
            setupDatabase();
            loadPlayerTimes();
            loadAfkServers();
        }

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
        hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getString("mysql.host") + ":" + config.getInt("mysql.port") + "/" + config.getString("mysql.database") + "?useSSL=" + config.getBoolean("mysql.useSSL", false));
        hikariConfig.setUsername(config.getString("mysql.username"));
        hikariConfig.setPassword(config.getString("mysql.password"));
        hikariConfig.setPoolName("CheckAFK-Pool");

        hikariConfig.setMaximumPoolSize(config.getInt("mysql.connection_pool.maximum-pool-size", 10));
        hikariConfig.setMinimumIdle(config.getInt("mysql.connection_pool.minimum-idle", 5));
        hikariConfig.setConnectionTimeout(config.getLong("mysql.connection_pool.connection-timeout", 30000));
        hikariConfig.setIdleTimeout(config.getLong("mysql.connection_pool.idle-timeout", 600000));
        hikariConfig.setMaxLifetime(config.getLong("mysql.connection_pool.max-lifetime", 1800000));

        dataSource = new HikariDataSource(hikariConfig);

        try (Connection connection = dataSource.getConnection()) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_times (uuid VARCHAR(36) PRIMARY KEY, afk_time INT NOT NULL DEFAULT 0)");
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "afk_servers (uuid VARCHAR(36) PRIMARY KEY, server VARCHAR(255) NOT NULL)");
            getLogger().info("数据库初始化成功");
        } catch (SQLException e) {
            getLogger().severe("无法初始化数据库: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds <= 0) {
                        sender.sendMessage(formatMessage("invalid-time"));
                        return true;
                    }
                    Player player = (Player) sender;
                    savePlayerTime(player.getUniqueId(), seconds);
                    sender.sendMessage(formatMessage("time-set", "minutes", String.valueOf(seconds)));
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
            return true;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        String currentTime = sdf.format(new Date());

        for (String range : timeRanges) {
            String[] parts = range.split("-");
            if (parts.length != 2) continue;

            try {
                String startTime = parts[0].trim();
                String endTime = parts[1].trim();
                if (currentTime.compareTo(startTime) >= 0 && currentTime.compareTo(endTime) <= 0) {
                    return false;
                }
            } catch (Exception e) {
                getLogger().warning("无效的时间范围格式: " + range);
            }
        }
        return true;
    }

    public int getPlayerTime(UUID uuid) {
        // 优先从数据库查询
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
        }

        // 如果无数据库记录，从data.yml获取
        if (data.contains("players." + uuid.toString())) {
            int time = data.getInt("players." + uuid.toString());
            playerTimes.put(uuid, time);
            return time;
        }

        // 默认值
        int defaultTime = config.getInt("time", config.getInt("default-afk-time", 300));
        playerTimes.put(uuid, defaultTime);
        return defaultTime;
    }

    private void savePlayerTime(UUID uuid, int seconds) {
        playerTimes.put(uuid, seconds);

        if (config.getBoolean("mysql.enabled", false)) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "INSERT INTO " + tablePrefix + "player_times (uuid, afk_time) VALUES (?, ?) ON DUPLICATE KEY UPDATE afk_time = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setInt(2, seconds);
                stmt.setInt(3, seconds);
                stmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("保存玩家时间失败: " + e.getMessage());
            }
        } else {
            data.set("players." + uuid.toString(), seconds);
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

    private void loadAfkServers() {
        if (dataSource != null) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "SELECT uuid, server FROM " + tablePrefix + "afk_servers";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String server = rs.getString("server");
                    afkServers.put(uuid, server);
                }
            } catch (SQLException e) {
                getLogger().warning("加载AFK服务器记录失败: " + e.getMessage());
            }
        }
    }

    public void recordAfkServer(UUID uuid, String server) {
        if (server == null || server.isEmpty()) return;
        afkServers.put(uuid, server);

        if (dataSource != null) {
            String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
            String sql = "INSERT INTO " + tablePrefix + "afk_servers (uuid, server) VALUES (?, ?) ON DUPLICATE KEY UPDATE server = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, server);
                stmt.setString(3, server);
                stmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("记录AFK服务器失败: " + e.getMessage());
            }
        }
    }

    public String getAfkServer(UUID uuid) {
        if (!config.getBoolean("mysql.enabled")) {
            getLogger().warning("MySQL 未启用，返回空服务器名称: UUID=" + uuid);
            return "";
        }
        String server = getAfkServerFromDatabase(uuid);
        if (server == null || server.isEmpty()) {
            getLogger().info("数据库无AFK服务器记录或记录为空: UUID=" + uuid);
            return "";
        }
        return server;
    }

    public String getAfkServerFromDatabase(UUID uuid) {
        if (dataSource == null) {
            getLogger().warning("数据库未初始化，无法查询AFK服务器记录: UUID=" + uuid);
            return null;
        }
        String tablePrefix = config.getString("mysql.table_prefix", "checkafk_");
        String sql = "SELECT server FROM " + tablePrefix + "afk_servers WHERE uuid = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String server = rs.getString("server");
                return server;
            } else {
                getLogger().info("数据库无AFK服务器记录: UUID=" + uuid);
            }
        } catch (SQLException e) {
            getLogger().warning("获取AFK服务器记录失败: UUID=" + uuid + ", 错误: " + e.getMessage());
        }
        return null;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
        getLogger().info("设置服务器名称: " + serverName);
    }
}