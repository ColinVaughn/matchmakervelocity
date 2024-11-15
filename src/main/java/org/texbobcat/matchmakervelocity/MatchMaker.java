package org.texbobcat.matchmakervelocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import java.time.Instant;

import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

public class MatchMaker {
    private final DatabaseManager databaseManager;
    private final ProxyServer server;
    private final Logger logger;
    private final Map<String, Queue<Player>> matchmakingQueues = new ConcurrentHashMap<>();
    private final Map<UUID, MatchInfo> playerMatches = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerQueueMode = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> playerQueueTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final int BASE_MMR_THRESHOLD = 100;
    private static final int MAX_QUEUE_WAIT_TIME = 300; // max seconds for queue
    private static final int RECHECK_INTERVAL = 5; // recheck every 5 seconds
    private static final int ESTIMATED_MATCH_FORMATION_TIME = 120; // estimated time to form a match in seconds
    private static final int MESSAGE_INTERVAL = 30; // interval to send queue time messages to players in seconds

    public MatchMaker(DatabaseManager databaseManager, ProxyServer server, Logger logger) {
        this.databaseManager = databaseManager;
        this.server = server;
        this.logger = logger;
        this.scheduler.scheduleAtFixedRate(this::recheckQueues, RECHECK_INTERVAL, RECHECK_INTERVAL, TimeUnit.SECONDS);
        this.scheduler.scheduleAtFixedRate(this::sendQueueTimeMessages, MESSAGE_INTERVAL, MESSAGE_INTERVAL, TimeUnit.SECONDS);
    }

    public void addToQueue(Player player, String mode) {
        UUID playerId = player.getUniqueId();
        logger.info("Attempting to add player " + player.getUsername() + " to queue for mode: " + mode);

        // Check if player is already in a queue
        if (this.playerQueueMode.containsKey(playerId)) {
            String currentMode = this.playerQueueMode.get(playerId);
            if (currentMode.equals(mode)) {
                logger.info("Player " + player.getUsername() + " is already queued for mode: " + mode);
                return;
            }
            this.removeFromQueue(player, currentMode);  // Remove from any previous queue mode
            logger.info("Player " + player.getUsername() + " removed from previous queue mode: " + currentMode);
        }

        // Add player to the specified queue
        matchmakingQueues.computeIfAbsent(mode, k -> new LinkedList<>()).add(player);
        playerQueueMode.put(playerId, mode);
        playerQueueTimes.put(playerId, Instant.now());

        logger.info("Player " + player.getUsername() + " successfully added to queue for mode: " + mode);
        triggerMatchmaking(mode);  // Immediately attempt matchmaking after adding player
    }


    private void recheckQueues() {
        matchmakingQueues.keySet().forEach(this::findMatch);
    }

    private void findMatch(String mode) {
        Queue<Player> queue = matchmakingQueues.get(mode);
        if (queue == null || queue.isEmpty()) return;

        // Remove players who have waited too long
        removeLongWaitingPlayers(queue, mode);

        int requiredPlayers = getRequiredPlayers(mode);
        if (queue.size() < requiredPlayers) return;

        List<Player> matchedPlayers = getMMRCompatiblePlayers(queue, requiredPlayers, mode);
        if (matchedPlayers.size() < requiredPlayers) return;

        Optional<DatabaseManager.ServerInfo> serverOpt = databaseManager.getAvailableServer(mode);
        if (serverOpt.isPresent()) {
            DatabaseManager.ServerInfo serverInfo = serverOpt.get();
            this.assignPlayersToServer(matchedPlayers, serverInfo);
        } else {
            logger.warning("No available server for mode: " + mode + ". Retrying in " + RECHECK_INTERVAL + " seconds.");
        }
    }

    private int getRequiredPlayers(String mode) {
        return switch (mode) {
            case "1v1" -> 1;
            case "4FFA" -> 4;
            case "8FFA" -> 8;
            default -> 0;
        };
    }

    // Method to trigger matchmaking immediately when players join or leave the queue
    private void triggerMatchmaking(String mode) {
        findMatch(mode);
    }

    // Send queue time updates and estimated wait time to each player in the queue
    private void sendQueueTimeMessages() {
        matchmakingQueues.forEach((mode, queue) -> {
            int queueSize = queue.size();
            int averageWaitTimePerPlayer = queueSize > 0 ? ESTIMATED_MATCH_FORMATION_TIME / queueSize : 0;

            int position = 0;
            for (Player player : queue) {
                UUID playerId = player.getUniqueId();
                Instant joinTime = playerQueueTimes.get(playerId);

                if (joinTime != null) {
                    long timeInQueue = Duration.between(joinTime, Instant.now()).getSeconds();
                    int estimatedWaitTime = averageWaitTimePerPlayer * position;

                    // Send message to the player
                    server.getPlayer(playerId).ifPresent(p ->
                            p.sendMessage(Component.text("You have been in queue for " + timeInQueue + " seconds. "
                                    + "Estimated time remaining: " + estimatedWaitTime + " seconds."))
                    );
                }
                position++;
            }
        });
    }

    // Get players sorted by wait time with an enhanced MMR threshold based on queue size and wait time
    private List<Player> getMMRCompatiblePlayers(Queue<Player> queue, int requiredPlayers, String mode) {
        List<Player> sortedPlayers = new ArrayList<>(queue);

        // Sort players by queue time to prioritize those who have waited longest
        sortedPlayers.sort(Comparator.comparing(player -> playerQueueTimes.get(player.getUniqueId())));

        int dynamicMMRThreshold = calculateAdaptiveMMRThreshold(mode, sortedPlayers);
        List<Player> matchedPlayers = new ArrayList<>();

        for (int i = 0; i <= sortedPlayers.size() - requiredPlayers; i++) {
            List<Player> potentialMatch = sortedPlayers.subList(i, i + requiredPlayers);
            if (isMMRCompatible(potentialMatch, dynamicMMRThreshold)) {
                matchedPlayers.addAll(potentialMatch);
                queue.removeAll(potentialMatch);
                break;
            }
        }
        return matchedPlayers;
    }

    // Enhanced MMR threshold based on queue saturation and average wait time
    private int calculateAdaptiveMMRThreshold(String mode, List<Player> sortedPlayers) {
        Queue<Player> queue = matchmakingQueues.get(mode);
        if (queue == null || queue.isEmpty()) return BASE_MMR_THRESHOLD;

        long totalQueueTime = queue.stream()
                .map(player -> Duration.between(playerQueueTimes.get(player.getUniqueId()), Instant.now()).toSeconds())
                .reduce(0L, Long::sum);
        long averageQueueTime = totalQueueTime / queue.size();

        // Calculate a saturation factor based on queue size (higher queue size widens threshold)
        int saturationFactor = Math.min(queue.size() / 10, 10); // max factor of 10

        // Final threshold combines base threshold, wait time influence, and saturation factor
        return (int) (BASE_MMR_THRESHOLD + saturationFactor * 20 + Math.pow((double) averageQueueTime / 30L, 1.5));
    }

    private boolean isMMRCompatible(List<Player> players, int mmrThreshold) {
        int maxMMR = players.stream()
                .mapToInt(player -> databaseManager.getPlayerMMR(player.getUniqueId().toString()))
                .max().orElse(0);
        int minMMR = players.stream()
                .mapToInt(player -> databaseManager.getPlayerMMR(player.getUniqueId().toString()))
                .min().orElse(0);
        return maxMMR - minMMR <= mmrThreshold;
    }

    private void assignPlayersToServer(List<Player> players, DatabaseManager.ServerInfo serverInfo) {
        String matchTag = UUID.randomUUID().toString();

        // Set server to running with the match tag
        databaseManager.updateServerStatusWithTag(serverInfo.serverName, matchTag);

        // Assign match tag to each player and move them to the server
        players.forEach(player -> {
            playerMatches.put(player.getUniqueId(), new MatchInfo(matchTag, serverInfo.serverName));
            databaseManager.assignMatchTag(player.getUniqueId().toString(), matchTag);
            playerQueueMode.remove(player.getUniqueId());
            playerQueueTimes.remove(player.getUniqueId());
            server.getPlayer(player.getUniqueId()).ifPresent(p ->
                    p.createConnectionRequest(server.getServer(serverInfo.serverName).get()).fireAndForget()
            );
        });

        logger.info("Match with tag " + matchTag + " created on server " + serverInfo.serverName + " for players: " + players);
    }

    private void removeFromQueue(Player player, String mode) {
        UUID playerId = player.getUniqueId();
        Queue<Player> queue = matchmakingQueues.get(mode);
        if (queue != null && queue.remove(player)) {
            logger.info("Player " + player.getUsername() + " successfully removed from queue for mode: " + mode);
        } else {
            logger.warning("Player " + player.getUsername() + " was not found in queue for mode: " + mode);
        }

        playerQueueMode.remove(playerId);
        playerQueueTimes.remove(playerId);

        logger.info("Cleared queue mode and queue time for player: " + player.getUsername());
        triggerMatchmaking(mode);
    }


    // Helper method to remove players who have exceeded MAX_QUEUE_WAIT_TIME
    private void removeLongWaitingPlayers(Queue<Player> queue, String mode) {
        Instant now = Instant.now();
        queue.removeIf(player -> {
            Instant joinTime = playerQueueTimes.get(player.getUniqueId());
            if (joinTime != null && Duration.between(joinTime, now).toSeconds() > MAX_QUEUE_WAIT_TIME) {
                logger.info("Player " + player.getUsername() + " removed from queue for exceeding max wait time in mode: " + mode);
                playerQueueMode.remove(player.getUniqueId());
                playerQueueTimes.remove(player.getUniqueId());
                return true;
            }
            return false;
        });
    }

    public void removePlayerOnDisconnect(Player player) {
        UUID playerId = player.getUniqueId();

        if (playerQueueMode.containsKey(playerId)) {
            String mode = playerQueueMode.get(playerId);
            removeFromQueue(player, mode);
            logger.info("Player " + player.getUsername() + " disconnected and was removed from queue for mode: " + mode);
        }
    }


    public void handlePlayerReconnection(Player player) {
        logger.info("Attempting to reconnect player: " + player.getUsername() + " with UUID: " + player.getUniqueId());

        // Retrieve the match tag for the player
        String playerId = player.getUniqueId().toString();
        String matchTag = databaseManager.getPlayerMatchTag(playerId);

        // If no match tag is found, assume the player is free to queue again
        if (matchTag == null) {
            logger.info("No active match found for player: " + player.getUsername() + ". Player can now re-queue.");
            logger.info("Automatically added player " + player.getUsername() + " to queue after reconnection.");
            return; // Exit to allow re-queue since no match tag exists
        }

        logger.info("Found match tag: " + matchTag + " for player: " + player.getUsername());

        // Attempt to find a server running with this match tag
        Optional<DatabaseManager.ServerInfo> serverInfo = databaseManager.getServerByMatchTag(matchTag);
        if (!serverInfo.isPresent()) {
            // No server is running the match, so assume the match has ended
            logger.warning("No running server found for match tag: " + matchTag + " for player: " + player.getUsername() + ". Clearing match tag to allow re-queue.");

            // Clear the player's match tag and related data
            databaseManager.removeMatchTag(playerId);
            playerMatches.remove(player.getUniqueId());  // Remove from in-memory matches
            playerQueueMode.remove(player.getUniqueId()); // Remove queue mode
            playerQueueTimes.remove(player.getUniqueId()); // Remove queue time

            // Automatically add the player back to the queue
            addToQueue(player, "1v1"); // Adjust the mode if needed
            logger.info("Automatically added player " + player.getUsername() + " to queue after match clearance.");
            return;
        }

        // If a running server with the match tag exists, proceed to reconnect the player
        String serverName = serverInfo.get().serverName;
        logger.info("Found server: " + serverName + " with matching tag for player: " + player.getUsername());

        // Verify if the server is registered and available in the proxy server list
        Optional<RegisteredServer> targetServer = server.getServer(serverName);
        if (!targetServer.isPresent()) {
            logger.warning("Server " + serverName + " is not registered or available for match reconnection for player: " + player.getUsername());
            return;
        }

        scheduler.schedule(() -> {
            try {
                player.createConnectionRequest(targetServer.get()).fireAndForget();
                logger.info("Successfully reconnected player " + player.getUsername() + " to server " + serverName);
            } catch (Exception e) {
                logger.severe("Error while reconnecting player " + player.getUsername() + " to server " + serverName + ": " + e.getMessage());
            }
        }, 1, TimeUnit.SECONDS);
    }


}






