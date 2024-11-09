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

        if (this.playerQueueMode.containsKey(playerId)) {
            String currentMode = this.playerQueueMode.get(playerId);
            if (currentMode.equals(mode)) {
                logger.info("Player " + player.getUsername() + " is already queued for mode: " + mode);
                return;
            }
            this.removeFromQueue(player, currentMode);
        }

        matchmakingQueues.computeIfAbsent(mode, k -> new LinkedList<>()).add(player);
        playerQueueMode.put(playerId, mode);
        playerQueueTimes.put(playerId, Instant.now());
        logger.info("Player " + player.getUsername() + " added to queue for mode: " + mode);

        // Immediately attempt to match after adding a player to the queue
        triggerMatchmaking(mode);
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
        switch (mode) {
            case "1v1": return 1;
            case "4FFA": return 4;
            case "8FFA": return 8;
            default: return 0;
        }
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
        return (int) (BASE_MMR_THRESHOLD + saturationFactor * 20 + Math.pow(averageQueueTime / 30L, 1.5));
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
        Queue<Player> queue = matchmakingQueues.get(mode);
        if (queue != null) {
            queue.remove(player);
            playerQueueMode.remove(player.getUniqueId());
            playerQueueTimes.remove(player.getUniqueId());
            logger.info("Player " + player.getUsername() + " removed from queue for mode: " + mode);
        }

        // Trigger matchmaking attempt after player leaves queue
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

        if (matchTag == null) {
            logger.warning("No match tag found for player: " + player.getUsername() + " (UUID: " + playerId + ")");
            return;
        }
        logger.info("Found match tag: " + matchTag + " for player: " + player.getUsername());

        // Attempt to find a server running with this match tag
        Optional<DatabaseManager.ServerInfo> serverInfo = databaseManager.getServerByMatchTag(matchTag);
        if (!serverInfo.isPresent()) {
            logger.warning("No running server found for match tag: " + matchTag + " for player: " + player.getUsername());
            return;
        }

        String serverName = serverInfo.get().serverName;
        logger.info("Found server: " + serverName + " with matching tag for player: " + player.getUsername());

        // Verify if the server is registered and available in the proxy server list
        Optional<RegisteredServer> targetServer = server.getServer(serverName);
        if (!targetServer.isPresent()) {
            logger.warning("Server " + serverName + " is not registered or available for match reconnection for player: " + player.getUsername());
            return;
        }

        // Delay the reconnection to give time for the server connection to fully establish
        scheduler.schedule(() -> {
            try {
                // Attempt to reconnect the player to the appropriate server
                player.createConnectionRequest(targetServer.get()).fireAndForget();
                logger.info("Successfully reconnected player " + player.getUsername() + " to server " + serverName);
            } catch (Exception e) {
                logger.severe("Error while reconnecting player " + player.getUsername() + " to server " + serverName + ": " + e.getMessage());
            }
        }, 1, TimeUnit.SECONDS); // Delay of 1 second before attempting to reconnect
    }


}






