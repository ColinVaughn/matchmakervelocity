package org.texbobcat.matchmakervelocity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.Optional;

import java.sql.DriverManager;

import java.sql.Statement;
import java.util.logging.Logger;

public class DatabaseManager {
    private String url;
    private String username;
    private String password;
    private Connection connection;

    public DatabaseManager(String ip, String port, String databaseName, String username, String password) {
        this.url = "jdbc:mysql://" + ip + ":" + port + "/" + databaseName + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.username = username;
        this.password = password;
    }


    public boolean connect() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");

            connection = DriverManager.getConnection(url, username, password);
            initializeDatabase();
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void initializeDatabase() {
        String createPlayersTable = "CREATE TABLE IF NOT EXISTS players (" +
                "player_id VARCHAR(36) PRIMARY KEY, " +
                "mmr INT NOT NULL" +
                ");";
        String createServersTable = "CREATE TABLE IF NOT EXISTS servers (" +
                "server VARCHAR(255) PRIMARY KEY, " +
                "type VARCHAR(50) NOT NULL, " +
                "gamerunning BOOLEAN NOT NULL" +
                ");";
        String createPlayerTagsTable = "CREATE TABLE IF NOT EXISTS player_tags (" +
                "player_id VARCHAR(36) PRIMARY KEY, " +
                "match_tag VARCHAR(36) NOT NULL" +
                ");";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createPlayersTable);
            statement.executeUpdate(createServersTable);
            statement.executeUpdate(createPlayerTagsTable);
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Failed to initialize database tables: " + e.getMessage());
        }
    }


    public int getPlayerMMR(String playerId) {
        String query = "SELECT mmr FROM players WHERE player_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, playerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Logger.getLogger("Found player with MMR: " + rs.getInt("mmr"));
                return rs.getInt("mmr");
            } else {
                insertPlayer(playerId, 1200);
                return 1200;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 1200; // Default MMR
    }
    private void insertPlayer(String playerId, int mmr) {
        String insertSQL = "INSERT INTO players (player_id, mmr) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(insertSQL)) {
            stmt.setString(1, playerId);
            stmt.setInt(2, mmr);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    public void assignMatchTag(String playerId, String matchTag) {
        String query = "INSERT INTO player_tags (player_id, match_tag) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE match_tag = VALUES(match_tag)";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, playerId);
            stmt.setString(2, matchTag);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error assigning match tag: " + e.getMessage());
        }
    }

    public void updateServerStatusWithTag(String serverName, String matchTag) {
        String query = "UPDATE servers SET gamerunning = true, unique_match_tag = ? WHERE server = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, matchTag);
            stmt.setString(2, serverName);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error updating server status with match tag: " + e.getMessage());
        }
    }
    public String getPlayerMatchTag(String playerId) {
        String query = "SELECT match_tag FROM player_tags WHERE player_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, playerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("match_tag");
            }
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error fetching match tag for player: " + e.getMessage());
        }
        return null;
    }
    public Optional<ServerInfo> getServerByMatchTag(String matchTag) {
        String query = "SELECT server, type FROM servers WHERE unique_match_tag = ? AND gamerunning = true LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, matchTag);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(new ServerInfo(rs.getString("server"), rs.getString("type")));
            }
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error fetching server for match tag: " + e.getMessage());
        }
        return Optional.empty();
    }
    public void clearServerMatchTag(String serverName) {
        String query = "UPDATE servers SET gamerunning = false, unique_match_tag = NULL WHERE server = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, serverName);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error clearing match tag from server: " + e.getMessage());
        }
    }

    // Method to remove a match tag from a player
    public void removeMatchTag(String playerId) {
        String query = "DELETE FROM player_tags WHERE player_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, playerId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error removing match tag: " + e.getMessage());
        }
    }
    public void updateServerStatus(String serverName, String type, boolean gameRunning) {
        String query = "INSERT INTO servers (server, type, gamerunning) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE type = VALUES(type), gamerunning = VALUES(gamerunning)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, serverName);
            statement.setString(2, type);
            statement.setBoolean(3, gameRunning);
            statement.executeUpdate();
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error updating server status: " + e.getMessage());
        }
    }

    public Optional<ServerInfo> getAvailableServer(String mode) {
        String query = "SELECT server, type FROM servers WHERE type = ? AND gamerunning = false LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, mode);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return Optional.of(new ServerInfo(rs.getString("server"), rs.getString("type")));
            }
        } catch (SQLException e) {
            Logger.getLogger("DatabaseManager").severe("Error fetching available server: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                Logger.getLogger("DatabaseManager").info("Database connection closed.");
            } catch (SQLException e) {
                Logger.getLogger("DatabaseManager").severe("Failed to close the connection: " + e.getMessage());
            }
        }
    }

    public static class ServerInfo {
        public final String serverName;
        public final String type;

        public ServerInfo(String serverName, String type) {
            this.serverName = serverName;
            this.type = type;
        }
    }
}
