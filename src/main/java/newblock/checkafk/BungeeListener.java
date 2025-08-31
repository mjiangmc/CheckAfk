package newblock.checkafk;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class BungeeListener implements PluginMessageListener {

    private final CheckAfk plugin;

    public BungeeListener(CheckAfk plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("BungeeCord")) return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();
        if (subChannel.equals("GetServer")) {
            String serverName = in.readUTF();
            plugin.setServerName(serverName);
            if (plugin.getPluginConfig().getBoolean("debug")) {
                plugin.getLogger().info(plugin.formatMessage("server-name-received", "server", serverName));
            }
        }
    }
}