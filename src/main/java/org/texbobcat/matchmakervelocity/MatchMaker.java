package org.texbobcat.matchmakervelocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import java.time.Instant;



public class MatchMaker {
    private final DatabaseManager databaseManager;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Map<String, List<QueueEntry>> modeQueues = new ConcurrentHashMap<>();
    private final Map<Player, String> playerQueueMap = new ConcurrentHashMap<>();
    private final Map<UUID, RegisteredServer> playerServerMap = new ConcurrentHashMap<>();
    private static final int BASE_MMR_THRESHOLD = 300;
    private static final int MAX_MMR_THRESHOLD = 1000;
    private static final long WAIT_TIME_THRESHOLD = 60_000;

    public MatchMaker(DatabaseManager databaseManager, ProxyServer proxyServer, Logger logger) {
        this.databaseManager = databaseManager;
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    // Method to add a player to the queue
    public void addToQueue(Player player, String mode) {
        String currentMode = playerQueueMap.get(player);

        if (currentMode != null) {
            if (currentMode.equals(mode)) {
                logger.info("Player " + player.getUsername() + " is already in the " + mode + " queue.");
                return; // Player is already in this queue
            }
            removeFromQueue(player, currentMode); // Remove from the existing queue
            logger.info("Player " + player.getUsername() + " moved from " + currentMode + " queue to " + mode + " queue.");
        }

        // Fetch MMR and check if valid
        int mmr = databaseManager.getPlayerMMR(player.getUniqueId().toString());
        if (mmr == -1) {
            logger.warning("MMR for player " + player.getUsername() + " not found.");
            return;
        }

        // Add player to new queue
        QueueEntry newEntry = new QueueEntry(player, mmr, mode, Instant.now());
        modeQueues.computeIfAbsent(mode, k -> new ArrayList<>()).add(newEntry);
        playerQueueMap.put(player, mode); // Track the player's current queue

        // Attempt to find a match if enough players are in queue
        findMatch(mode);
    }

    // Method to remove a player from the queue
    private void removeFromQueue(Player player, String mode) {
        List<QueueEntry> queueForMode = modeQueues.get(mode);
        if (queueForMode != null) {
            queueForMode.removeIf(entry -> entry.player.equals(player));
            playerQueueMap.remove(player); // Clear player's queue status
            logger.info("Player " + player.getUsername() + " removed from " + mode + " queue.");
        }
    }

    // Method to handle player disconnection
    public void handlePlayerDisconnection(Player player) {
        UUID playerId = player.getUniqueId();
        RegisteredServer gameServer = playerServerMap.get(playerId);

        if (gameServer != null) {
            // The player was in a match; mark them as disconnected
            logger.info("Player " + player.getUsername() + " disconnected from an ongoing match. Sending to the main lobby...");

            // Send the player to the main lobby
            sendPlayerToLobby(player);

            // Note: We do not remove their entry from `playerServerMap` or clear the match tag
        } else {
            // The player was not in a match, remove them from the queue
            String mode = playerQueueMap.get(player);
            if (mode != null) {
                removeFromQueue(player, mode);
                logger.info("Player " + player.getUsername() + " disconnected and removed from the " + mode + " queue.");
            }
        }
    }


    // Method to handle player reconnection
    public void handlePlayerReconnection(Player player) {
        UUID playerId = player.getUniqueId();
        String playerTag = databaseManager.getPlayerMatchTag(playerId.toString());

        if (playerTag == null) {
            logger.info("Player " + player.getUsername() + " does not have an active match tag. They will remain in the lobby.");
            return;
        }

        // Check if there's a server running with the matching tag
        Optional<DatabaseManager.ServerInfo> serverInfoOptional = databaseManager.getServerByMatchTag(playerTag);
        if (serverInfoOptional.isPresent()) {
            // Reconnect the player to the game server if the match is still active
            Optional<RegisteredServer> gameServerOptional = proxyServer.getServer(serverInfoOptional.get().serverName);
            if (gameServerOptional.isPresent()) {
                RegisteredServer gameServer = gameServerOptional.get();
                try {
                    player.createConnectionRequest(gameServer).connect();
                    logger.info("Reconnected player " + player.getUsername() + " to game server " + gameServer.getServerInfo().getName());

                    // Track the server the player is reconnected to
                    playerServerMap.put(playerId, gameServer);
                } catch (Exception e) {
                    logger.warning("Failed to reconnect player " + player.getUsername() + " to game server: " + e.getMessage());
                }
            } else {
                logger.warning("Registered server not found for match tag: " + playerTag);
            }
        } else {
            // No server with the same match tag was found, implying the match is over
            logger.info("No active match found for player " + player.getUsername() + " with tag " + playerTag + ". They will remain in the lobby.");
        }
    }

    private void sendPlayerToLobby(Player player) {
        Optional<RegisteredServer> lobbyServerOptional = proxyServer.getServer("lobby"); // Replace "main_lobby" with your lobby server name
        if (lobbyServerOptional.isPresent()) {
            RegisteredServer lobbyServer = lobbyServerOptional.get();
            try {
                player.createConnectionRequest(lobbyServer).connect();
                logger.info("Player " + player.getUsername() + " sent to the main lobby.");
            } catch (Exception e) {
                logger.warning("Failed to send player " + player.getUsername() + " to the main lobby: " + e.getMessage());
            }
        } else {
            logger.warning("Lobby server not found. Cannot send player " + player.getUsername() + " to the lobby.");
        }
    }

    private void findMatch(String mode) {
        int requiredPlayers = switch (mode) {
            case "1v1" -> 1;
            case "4FFA" -> 4;
            case "8FFA" -> 8;
            default -> 0;
        };

        List<QueueEntry> queueForMode = modeQueues.get(mode);
        if (queueForMode == null || queueForMode.size() < requiredPlayers) {
            return; // Not enough players yet for this mode
        }

        long currentTime = Instant.now().toEpochMilli();
        long totalWaitTime = queueForMode.stream()
                .mapToLong(entry -> currentTime - entry.joinTime.toEpochMilli())
                .sum();
        long avgWaitTime = totalWaitTime / queueForMode.size();

        int mmrThreshold = avgWaitTime > WAIT_TIME_THRESHOLD
                ? Math.min(MAX_MMR_THRESHOLD, BASE_MMR_THRESHOLD + (int) (avgWaitTime - WAIT_TIME_THRESHOLD) / 10)
                : BASE_MMR_THRESHOLD;

        for (QueueEntry entry : new ArrayList<>(queueForMode)) {
            List<QueueEntry> mmrCompatibleEntries = queueForMode.stream()
                    .filter(e -> Math.abs(e.mmr - entry.mmr) <= mmrThreshold)
                    .limit(requiredPlayers)
                    .toList();

            if (mmrCompatibleEntries.size() == requiredPlayers) {
                List<Player> matchedPlayers = mmrCompatibleEntries.stream().map(e -> e.player).toList();
                matchedPlayers.forEach(playerQueueMap::remove); // Remove players from queue tracking

                mmrCompatibleEntries.forEach(queueForMode::remove); // Remove matched players from the mode queue

                Optional<DatabaseManager.ServerInfo> availableServer = databaseManager.getAvailableServer(mode);
                if (availableServer.isPresent()) {
                    Optional<RegisteredServer> gameServerOptional = proxyServer.getServer(availableServer.get().serverName);
                    if (gameServerOptional.isPresent()) {
                        RegisteredServer gameServer = gameServerOptional.get();
                        sendPlayersToServer(matchedPlayers, gameServer);
                        databaseManager.updateServerStatus(availableServer.get().serverName, mode, true); // Set gameRunning to true

                        // Track the server each player is connected to
                        matchedPlayers.forEach(player -> playerServerMap.put(player.getUniqueId(), gameServer));
                    } else {
                        logger.warning("Registered server not found for name: " + availableServer.get().serverName);
                    }
                } else {
                    logger.warning("No available server found for mode: " + mode);
                    queueForMode.addAll(mmrCompatibleEntries); // Re-add players back to the queue if no server is available
                }
                break; // Exit loop after processing a match
            }
        }
    }

    private void sendPlayersToServer(List<Player> players, RegisteredServer gameServer) {
        String matchTag = UUID.randomUUID().toString(); // Generate a unique match tag for the match

        // Assign the match tag to each player
        players.forEach(player -> {
            try {
                player.createConnectionRequest(gameServer).connect();
                databaseManager.assignMatchTag(player.getUniqueId().toString(), matchTag);
                logger.info("Sent player " + player.getUsername() + " to game server " + gameServer.getServerInfo().getName() + " with match tag " + matchTag);
            } catch (Exception e) {
                logger.warning("Failed to connect player " + player.getUsername() + " to game server: " + e.getMessage());
            }
        });

        // Assign the same match tag to the server in the database
        databaseManager.updateServerStatusWithTag(gameServer.getServerInfo().getName(), matchTag);
    }


    public void clearMatchTags(List<Player> players, RegisteredServer gameServer) {
        players.forEach(player -> {
            databaseManager.removeMatchTag(player.getUniqueId().toString());
            logger.info("Cleared match tag for player " + player.getUsername());
        });

        // Clear the match tag from the server in the database
        databaseManager.clearServerMatchTag(gameServer.getServerInfo().getName());
    }

    private static class QueueEntry {
        Player player;
        int mmr;
        String mode;
        Instant joinTime;

        QueueEntry(Player player, int mmr, String mode, Instant joinTime) {
            this.player = player;
            this.mmr = mmr;
            this.mode = mode;
            this.joinTime = joinTime;
        }
    }
}
