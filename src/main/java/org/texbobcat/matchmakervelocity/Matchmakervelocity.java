package org.texbobcat.matchmakervelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

@Plugin(id = "matchmakervelocity", name = "matchmakervelocity", version = BuildConstants.VERSION)
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
        // Initialize the database manager
        databaseManager = new DatabaseManager(
                "45.59.171.188",
                "3306",
                "s4003_Matchmaker",
                "u4003_MlzPkUjVqI",
                "+7n3P6Shn3D=4=N1b8Qlt@YS"
        );

        // Attempt to connect to the database
        if (databaseManager.connect()) {
            logger.info("Database connection successful.");
        } else {
            logger.severe("Database connection failed.");
            return; // Exit if the database connection fails
        }

        // Initialize the MatchMaker
        matchmaker = new MatchMaker(databaseManager, server, logger);
        logger.info("Matchmaking system initialized.");

        // Register the PlayerDisconnectListener
        server.getEventManager().register(this, new PlayerDisconnectListener(matchmaker));

        // Register the plugin channel
        server.getChannelRegistrar().register(MinecraftChannelIdentifier.create("matchmaking", "mode"));

        // Register the event listener for player login
        server.getEventManager().register(this, new Object() {
            @Subscribe
            public void onPlayerLogin(LoginEvent event) {
                Optional<Player> playerOptional = server.getPlayer(event.getPlayer().getUniqueId());
                playerOptional.ifPresent(player -> matchmaker.handlePlayerReconnection(player));
            }
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals("matchmaking:mode")) return;

        String data = new String(event.getData());
        String[] parts = data.split(":");

        if (parts.length < 2) {
            logger.warning("Invalid plugin message format.");
            return;
        }

        UUID playerUUID = UUID.fromString(parts[0]);
        String mode = parts[1];

        Optional<Player> playerOptional = server.getPlayer(playerUUID);
        if (playerOptional.isPresent()) {
            Player player = playerOptional.get();
            matchmaker.addToQueue(player, mode);
        } else {
            logger.warning("Player with UUID " + playerUUID + " not found on the proxy.");
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        databaseManager.close();
    }
}
