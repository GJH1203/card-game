package com.cardgame.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "games")
public class GameModel {

    @Id // Marks the primary key for this document's ID
    private String id;

    private GameState gameState;

    private Board board;

    private Instant createdAt;

    private Instant updatedAt;

    private String currentPlayerId;

    private List<String> playerIds;

    // Game result fields
    private String winnerId;
    private boolean isTie;
    private Map<String, Integer> playerScores;

    // Win request fields
    private boolean hasPendingWinRequest;
    private String pendingWinRequestPlayerId;

    // constructor, getters, and setters
    public GameModel() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.playerScores = new HashMap<>();
        this.hasPendingWinRequest = false;
    }

    public GameModel(String id, GameState gameState, Board board) {
        this.id = id;
        this.gameState = gameState;
        this.board = board;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.playerScores = new HashMap<>();
        this.hasPendingWinRequest = false;
    }

    // Win request getters and setters
    public boolean hasPendingWinRequest() {
        return hasPendingWinRequest;
    }

    public void setHasPendingWinRequest(boolean hasPendingWinRequest) {
        this.hasPendingWinRequest = hasPendingWinRequest;
    }

    public String getPendingWinRequestPlayerId() {
        return pendingWinRequestPlayerId;
    }

    public void setPendingWinRequestPlayerId(String pendingWinRequestPlayerId) {
        this.pendingWinRequestPlayerId = pendingWinRequestPlayerId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public GameState getGameState() {
        return gameState;
    }

    public void setGameState(GameState gameState) {
        this.gameState = gameState;
    }

    public Board getBoard() {
        return board;
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCurrentPlayerId() {
        return currentPlayerId;
    }

    public void setCurrentPlayerId(String currentPlayerId) {
        this.currentPlayerId = currentPlayerId;
    }

    public List<String> getPlayerIds() {
        return playerIds;
    }

    public void setPlayerIds(List<String> playerIds) {
        this.playerIds = playerIds;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(String winnerId) {
        this.winnerId = winnerId;
    }

    public boolean isTie() {
        return isTie;
    }

    public void setTie(boolean tie) {
        isTie = tie;
    }

    public Map<String, Integer> getPlayerScores() {
        return playerScores;
    }

    public void setPlayerScores(Map<String, Integer> playerScores) {
        this.playerScores = playerScores;
    }

    /**
     * Add or update a player's score in the scores map.
     *
     * @param playerId The ID of the player
     * @param score The player's score
     */
    public void updatePlayerScore(String playerId, int score) {
        if (this.playerScores == null) {
            this.playerScores = new HashMap<>();
        }
        this.playerScores.put(playerId, score);
    }

    @Override
    public String toString() {
        return "Game{" +
                "id='" + id + '\'' +
                ", gameState=" + gameState +
                ", board=" + board +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", currentPlayerId='" + currentPlayerId + '\'' +
                ", playerIds=" + playerIds +
                ", winnerId='" + winnerId + '\'' +
                ", isTie=" + isTie +
                ", playerScores=" + playerScores +
                ", hasPendingWinRequest=" + hasPendingWinRequest +
                ", pendingWinRequestPlayerId='" + pendingWinRequestPlayerId + '\'' +
                '}';
    }

    public void updateGame(GameState gameState, Board board) {
        this.gameState = gameState;
        this.board = board;
        this.updatedAt = Instant.now();
    }

    /**
     * Get the scores map for all players in this game.
     * @return Map of player IDs to their current scores
     */
    public Map<String, Integer> getScores() {
        // Ensure scores map is never null
        if (playerScores == null) {
            playerScores = new HashMap<>();
        }
        return playerScores;
    }

    public void setScores(Map<String, Integer> scores) {
        this.playerScores = scores;
    }
}
