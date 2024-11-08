package org.texbobcat.matchmakervelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

@Plugin(
        id = "matchmakervelocity",
        name = "matchmakervelocity",
        version = "1.0-SNAPSHOT"
)
public class Matchmakervelocity {
    private final ProxyServer server;
    private final Logger logger;
    private DatabaseManager databaseManager;
    private MatchMaker matchmaker;

    @Inject
    public Matchmakervelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.databaseManager = new DatabaseManager("45.59.171.188", "3306", "s4003_Matchmaker", "u4003_MlzPkUjVqI", "+7n3P6Shn3D=4=N1b8Qlt@YS");
        if (this.databaseManager.connect()) {
            this.logger.info("Database connection successful.");
            this.matchmaker = new MatchMaker(this.databaseManager, this.server, this.logger);
            this.logger.info("Matchmaking system initialized.");
            this.server.getChannelRegistrar().register(new ChannelIdentifier[]{MinecraftChannelIdentifier.create("matchmaking", "mode")});
            this.server.getEventManager().register(this, new Object() {
                @Subscribe
                public void onPlayerLogin(LoginEvent event) {
                    Optional<Player> playerOptional = Matchmakervelocity.this.server.getPlayer(event.getPlayer().getUniqueId());
                    playerOptional.ifPresent((player) -> {
                        Matchmakervelocity.this.matchmaker.handlePlayerReconnection(player);
                    });
                }

                // New event listener for player disconnection
                @Subscribe
                public void onPlayerDisconnect(DisconnectEvent event) {
                    UUID playerUUID = event.getPlayer().getUniqueId();
                    server.getPlayer(playerUUID).ifPresent(player -> matchmaker.removePlayerOnDisconnect(player));
                }
            });
        } else {
            this.logger.severe("Database connection failed.");
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().getId().equals("matchmaking:mode")) {
            String data = new String(event.getData());
            String[] parts = data.split(":");
            if (parts.length < 2) {
                this.logger.warning("Invalid plugin message format.");
            } else {
                UUID playerUUID = UUID.fromString(parts[0]);
                String mode = parts[1];
                Optional<Player> playerOptional = this.server.getPlayer(playerUUID);
                if (playerOptional.isPresent()) {
                    Player player = playerOptional.get();
                    this.matchmaker.addToQueue(player, mode);
                } else {
                    this.logger.warning("Player with UUID " + playerUUID + " not found on the proxy.");
                }
            }
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        this.databaseManager.close();
    }
}
