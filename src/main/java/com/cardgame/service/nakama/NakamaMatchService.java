package com.cardgame.service.nakama;

import com.cardgame.model.*;
import com.cardgame.repository.GameRepository;
import com.cardgame.service.GameService;
import com.cardgame.service.player.PlayerService;
import com.cardgame.websocket.GameWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heroiclabs.nakama.Client;
import com.heroiclabs.nakama.Session;
import com.heroiclabs.nakama.SocketClient;
import com.heroiclabs.nakama.api.ChannelMessage;
import com.heroiclabs.nakama.api.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NakamaMatchService {
    private static final Logger logger = LoggerFactory.getLogger(NakamaMatchService.class);
    
    @Autowired
    private Client nakamaClient;
    
    @Autowired
    private GameRepository gameRepository;
    
    @Autowired
    private GameService gameService;
    
    @Autowired
    private PlayerService playerService;
    
    @Autowired
    private NakamaAuthService nakamaAuthService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    @Lazy  // Use @Lazy to avoid circular dependency
    private GameWebSocketHandler gameWebSocketHandler;
    
    // Store active socket connections
    private final Map<String, SocketClient> activeSockets = new ConcurrentHashMap<>();
    
    // Store match subscriptions
    private final Map<String, Set<String>> matchSubscriptions = new ConcurrentHashMap<>();
    
    // Store match metadata before game creation
    private final Map<String, MatchMetadata> matchMetadata = new ConcurrentHashMap<>();
    
    /**
     * Create a new online match
     * @param playerId The ID of the player creating the match
     * @return The match ID
     */
    public CompletableFuture<String> createMatch(String playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Creating match - received playerId: {}", playerId);
                
                // Clear any existing matches for this player first
                clearPlayerFromActiveMatches(playerId);
                
                // Generate a unique match ID
                String matchId = generateMatchId();
                
                // Add player to match subscriptions
                matchSubscriptions.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
                
                // Store match metadata
                MatchMetadata metadata = new MatchMetadata();
                metadata.creatorId = playerId;
                metadata.matchId = matchId;
                metadata.status = "WAITING";
                metadata.createdAt = Instant.now();
                matchMetadata.put(matchId, metadata);
                
                logger.info("Created match {} for player {} - metadata stored with creatorId: {}", 
                    matchId, playerId, metadata.creatorId);
                logger.info("Current matches after creation: {}", matchMetadata.keySet());
                
                return matchId;
            } catch (Exception e) {
                logger.error("Failed to create match for player {}", playerId, e);
                throw new RuntimeException("Failed to create match", e);
            }
        });
    }
    
    /**
     * Join an existing match
     * @param playerId The ID of the player joining
     * @param matchId The match ID to join
     * @return The GameModel for the match
     */
    public CompletableFuture<GameModel> joinMatch(String playerId, String matchId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Validate match exists and is waiting for players
                logger.info("Attempting to join match {}. Current matches in memory: {}", matchId, matchMetadata.keySet());
                MatchMetadata metadata = matchMetadata.get(matchId);
                if (metadata == null) {
                    logger.error("Match {} not found. Available matches: {}", matchId, matchMetadata.keySet());
                    logger.error("Match metadata map contains {} entries. Keys: {}", matchMetadata.size(), matchMetadata.keySet());
                    throw new IllegalArgumentException("Match not found");
                }
                
                if (!"WAITING".equals(metadata.status)) {
                    throw new IllegalArgumentException("Match already started or completed");
                }
                
                // Clear any existing matches for this player first
                clearPlayerFromActiveMatches(playerId);
                
                // Get the creator's ID from metadata
                String creatorId = metadata.creatorId;
                
                logger.info("Joining match {} - playerId: {}, creatorId from metadata: {}", 
                    matchId, playerId, creatorId);
                
                // Add player to match subscriptions
                Set<String> subscribers = matchSubscriptions.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet());
                subscribers.add(playerId);
                
                // Create the actual game in our backend
                logger.info("Looking up players - creator: {}, joiner: {}", creatorId, playerId);
                Player player1 = playerService.getPlayer(creatorId);
                Player player2 = playerService.getPlayer(playerId);
                
                // Ensure players have current decks set
                if (player1.getCurrentDeck() == null && player1.getOriginalDeck() != null) {
                    player1.setCurrentDeck(player1.getOriginalDeck());
                    playerService.savePlayer(player1);
                    logger.info("Set current deck for player1 from original deck");
                }
                
                if (player2.getCurrentDeck() == null && player2.getOriginalDeck() != null) {
                    player2.setCurrentDeck(player2.getOriginalDeck());
                    playerService.savePlayer(player2);
                    logger.info("Set current deck for player2 from original deck");
                }
                
                // Get deck IDs - handle lazy loading issues
                String deck1Id = null;
                String deck2Id = null;
                
                // For player 1
                if (player1.getCurrentDeck() != null && player1.getCurrentDeck().getId() != null) {
                    deck1Id = player1.getCurrentDeck().getId();
                } else if (player1.getOriginalDeck() != null && player1.getOriginalDeck().getId() != null) {
                    deck1Id = player1.getOriginalDeck().getId();
                    logger.info("Using original deck ID for player1 due to lazy loading");
                } else {
                    throw new RuntimeException("Player 1 has no deck available");
                }
                
                // For player 2
                if (player2.getCurrentDeck() != null && player2.getCurrentDeck().getId() != null) {
                    deck2Id = player2.getCurrentDeck().getId();
                } else if (player2.getOriginalDeck() != null && player2.getOriginalDeck().getId() != null) {
                    deck2Id = player2.getOriginalDeck().getId();
                    logger.info("Using original deck ID for player2 due to lazy loading");
                } else {
                    throw new RuntimeException("Player 2 has no deck available");
                }
                
                logger.info("Using decks - player1: {}, player2: {}", deck1Id, deck2Id);
                
                // Initialize the game - this returns GameDto
                var gameDto = gameService.initializeGame(
                    player1.getId(), 
                    player2.getId(),
                    deck1Id,
                    deck2Id
                );
                
                // Get the actual GameModel
                GameModel game = gameService.getGameModel(gameDto.getId());
                
                // Set online mode fields
                game.setGameMode(GameMode.ONLINE);
                game.setNakamaMatchId("nakama_" + matchId);
                
                // Initialize player connections
                Map<String, ConnectionStatus> connections = new HashMap<>();
                connections.put(creatorId, ConnectionStatus.CONNECTED);
                connections.put(playerId, ConnectionStatus.CONNECTED);
                game.setPlayerConnections(connections);
                
                game.setLastSyncTime(Instant.now());
                
                // Save the updated game
                game = gameRepository.save(game);
                
                // Update match metadata
                metadata.status = "IN_PROGRESS";
                metadata.gameId = game.getId();
                
                // Notify both players that the game has started
                broadcastMatchStart(matchId, game);
                
                // Also broadcast the full game state through WebSocket
                if (gameWebSocketHandler != null) {
                    // Get game DTO for each player and send updates
                    for (String pid : Arrays.asList(creatorId, playerId)) {
                        var playerGameDto = gameService.convertToDto(game, pid);
                        
                        // This will send to all connected players in the match
                        gameWebSocketHandler.broadcastGameUpdate(matchId, playerGameDto);
                    }
                }
                
                logger.info("Player {} joined match {}, game started", playerId, matchId);
                
                return game;
            } catch (Exception e) {
                logger.error("Failed to join match {} for player {}", matchId, playerId, e);
                throw new RuntimeException("Failed to join match", e);
            }
        });
    }
    
    /**
     * Send a game action to all players in the match
     * @param matchId The match ID
     * @param action The player action to broadcast
     */
    public CompletableFuture<Void> sendGameAction(String matchId, Map<String, Object> action) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Find the game by match ID
                GameModel game = getMatchState(matchId);
                
                // Process the action
                String actionType = (String) action.get("type");
                String playerId = (String) action.get("playerId");
                
                // Validate it's the player's turn
                if (!game.getCurrentPlayerId().equals(playerId)) {
                    logger.warn("Player {} tried to move out of turn", playerId);
                    return;
                }
                
                // Update game state based on action
                // This is simplified - in production you'd process different action types
                if ("PLACE_CARD".equals(actionType)) {
                    // Update game state
                    game.setLastSyncTime(Instant.now());
                    gameRepository.save(game);
                }
                
                // Game state update will be handled by WebSocket handler
                logger.info("Processed action for match {}", matchId);
            } catch (Exception e) {
                logger.error("Failed to send game action to match {}", matchId, e);
                throw new RuntimeException("Failed to send game action", e);
            }
        });
    }
    
    /**
     * Check if a match exists and is waiting for players
     * @param matchId The match ID
     * @return true if match exists and is waiting
     */
    public boolean isMatchWaiting(String matchId) {
        MatchMetadata metadata = matchMetadata.get(matchId);
        return metadata != null && "WAITING".equals(metadata.status);
    }
    
    /**
     * Get match metadata
     * @param matchId The match ID
     * @return The match metadata or null if not found
     */
    public MatchMetadata getMatchMetadata(String matchId) {
        return matchMetadata.get(matchId);
    }
    
    /**
     * Get the current state of a match
     * @param matchId The match ID
     * @return The current game state
     */
    public GameModel getMatchState(String matchId) {
        // Find game by Nakama match ID
        Optional<GameModel> game = gameRepository.findAll().stream()
            .filter(g -> g.getNakamaMatchId() != null && g.getNakamaMatchId().contains(matchId))
            .findFirst();
            
        return game.orElseThrow(() -> new IllegalArgumentException("Match not found"));
    }
    
    /**
     * Handle player disconnection
     * @param playerId The ID of the disconnected player
     * @param matchId The match ID
     */
    public void handleDisconnection(String playerId, String matchId) {
        try {
            // Try to update connection status in game if it exists
            try {
                GameModel game = getMatchState(matchId);
                if (game.getPlayerConnections() != null) {
                    game.getPlayerConnections().put(playerId, ConnectionStatus.DISCONNECTED);
                    game.setLastSyncTime(Instant.now());
                    gameRepository.save(game);
                }
            } catch (IllegalArgumentException e) {
                // Game doesn't exist in database yet - this is ok for matches that haven't started
                logger.debug("Game not found for match {} during disconnection - match may be waiting for players", matchId);
            }
            
            // Remove from active connections
            activeSockets.remove(playerId);
            
            // Notify other players
            Set<String> subscribers = matchSubscriptions.get(matchId);
            if (subscribers != null) {
                subscribers.remove(playerId);
                
                // Broadcast disconnection event
                Map<String, Object> event = new HashMap<>();
                event.put("type", "PLAYER_DISCONNECTED");
                event.put("playerId", playerId);
                broadcastEvent(matchId, event);
            }
            
            logger.info("Player {} disconnected from match {}", playerId, matchId);
        } catch (Exception e) {
            logger.error("Error handling disconnection for player {} in match {}", playerId, matchId, e);
        }
    }
    
    /**
     * Check if a game exists in the database for a given match
     * @param matchId The match ID
     * @return true if a game exists for this match
     */
    public boolean doesGameExistForMatch(String matchId) {
        try {
            getMatchState(matchId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * Clean up match resources
     * @param matchId The match ID to clean up
     */
    public void cleanupMatch(String matchId) {
        Set<String> subscribers = matchSubscriptions.remove(matchId);
        if (subscribers != null) {
            for (String playerId : subscribers) {
                activeSockets.remove(playerId);
            }
        }
        logger.info("Cleaned up match {}", matchId);
    }
    
    /**
     * Clear all active matches and sessions (for development/admin use)
     */
    public void clearAllMatches() {
        logger.info("Clearing all active matches...");
        
        // Clear all match metadata
        matchMetadata.clear();
        
        // Clear all match subscriptions
        matchSubscriptions.clear();
        
        // Clear active sockets
        activeSockets.clear();
        
        // Clear WebSocket sessions if handler is available
        if (gameWebSocketHandler != null) {
            gameWebSocketHandler.clearAllSessions();
        }
        
        logger.info("All matches cleared");
    }
    
    /**
     * Clear matches for a specific player
     */
    public void clearPlayerMatches(String playerId) {
        logger.info("Clearing matches for player: {}", playerId);
        
        // Only remove matches that are at least 5 seconds old to prevent race conditions
        Instant cutoffTime = Instant.now().minusSeconds(5);
        
        // Find all games where this player is involved
        List<GameModel> playerGames = gameRepository.findByPlayerIdsContaining(playerId);
        
        // Remove match metadata for completed games or old matches
        matchMetadata.entrySet().removeIf(entry -> {
            MatchMetadata meta = entry.getValue();
            String matchId = entry.getKey();
            
            // Check if this match has a completed game
            boolean hasCompletedGame = playerGames.stream()
                .anyMatch(game -> game.getNakamaMatchId() != null && 
                         game.getNakamaMatchId().equals(matchId) && 
                         game.getGameState() == GameState.COMPLETED);
            
            // Remove if:
            // 1. Match has a completed game, OR
            // 2. Player is creator and match is old enough
            boolean shouldRemove = hasCompletedGame || 
                                  (meta.creatorId != null && 
                                   meta.creatorId.equals(playerId) &&
                                   meta.createdAt != null &&
                                   meta.createdAt.isBefore(cutoffTime));
                                   
            if (shouldRemove) {
                logger.info("Removing match {} for player {} (completed: {})", 
                    matchId, playerId, hasCompletedGame);
            }
            return shouldRemove;
        });
        
        // Remove player from all subscriptions
        matchSubscriptions.values().forEach(subscribers -> subscribers.remove(playerId));
        
        // Clear player's WebSocket sessions
        if (gameWebSocketHandler != null) {
            gameWebSocketHandler.clearPlayerSessions(playerId);
        }
        
        logger.info("Cleared matches for player: {}", playerId);
    }
    
    /**
     * Get all active matches (for debugging)
     */
    public Map<String, MatchMetadata> getAllActiveMatches() {
        return new HashMap<>(matchMetadata);
    }
    
    /**
     * Find an active (non-completed) ONLINE game for a player
     * @param playerId The player ID to search for
     * @return The active GameModel or null if none found
     */
    public GameModel findActiveGameForPlayer(String playerId) {
        try {
            // Define active game states
            List<GameState> activeStates = Arrays.asList(
                GameState.INITIALIZED, 
                GameState.IN_PROGRESS
            );
            
            // Use efficient repository query to find the most recent active ONLINE game
            Optional<GameModel> activeGame = gameRepository
                .findFirstByPlayerIdsContainingAndGameStateInAndGameModeOrderByUpdatedAtDesc(
                    playerId, activeStates, GameMode.ONLINE
                );
            
            if (activeGame.isPresent()) {
                GameModel game = activeGame.get();
                logger.info("Found active ONLINE game {} in state {} for player {}", 
                    game.getId(), game.getGameState(), playerId);
                
                // Additional validation - ensure the game is truly active
                if (game.getGameState() == GameState.COMPLETED) {
                    logger.error("ERROR: Repository returned COMPLETED game as active! Game: {}", game.getId());
                    return null;
                }
                
                return game;
            } else {
                logger.info("No active ONLINE games found for player {}", playerId);
                return null;
            }
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while finding active game for player {}: {}", playerId, e.getMessage());
            return null;
        } catch (NoSuchElementException e) {
            logger.error("No element found while finding active game for player {}: {}", playerId, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error finding active game for player {}", playerId, e);
            throw new RuntimeException("Unexpected error occurred while finding active game for player " + playerId, e);
        }
    }
    
    // Helper methods
    
    private String generateMatchId() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
    
    /**
     * Clear a player from all active matches they might be in
     * This is called before creating/joining a new match to prevent stuck states
     */
    private void clearPlayerFromActiveMatches(String playerId) {
        logger.info("Clearing player {} from any active matches", playerId);
        
        // First, mark any IN_PROGRESS games as completed (abandoned)
        GameModel activeGame = findActiveGameForPlayer(playerId);
        if (activeGame != null && activeGame.getGameState() == GameState.IN_PROGRESS) {
            logger.info("Marking active game {} as abandoned for player {}", activeGame.getId(), playerId);
            activeGame.setGameState(GameState.COMPLETED);
            activeGame.setUpdatedAt(Instant.now());
            // Set winner as the other player (if any)
            String opponentId = activeGame.getPlayerIds().stream()
                .filter(id -> !id.equals(playerId))
                .findFirst()
                .orElse(null);
            if (opponentId != null) {
                activeGame.setWinnerId(opponentId);
                logger.info("Set winner as opponent {} due to abandonment", opponentId);
            }
            gameRepository.save(activeGame);
            
            // Notify other player about game completion through match broadcast
            if (gameWebSocketHandler != null && activeGame.getNakamaMatchId() != null) {
                Map<String, Object> notification = new HashMap<>();
                notification.put("type", "GAME_ABANDONED");
                notification.put("gameId", activeGame.getId());
                notification.put("gameState", "COMPLETED");
                notification.put("winnerId", opponentId);
                notification.put("message", "Opponent has left the game");
                gameWebSocketHandler.broadcastGameUpdate(activeGame.getNakamaMatchId(), notification);
            }
        }
        
        // Find and remove matches where player is creator and still waiting
        List<String> matchesToRemove = new ArrayList<>();
        for (Map.Entry<String, MatchMetadata> entry : matchMetadata.entrySet()) {
            MatchMetadata meta = entry.getValue();
            if (meta.creatorId != null && meta.creatorId.equals(playerId) && "WAITING".equals(meta.status)) {
                matchesToRemove.add(entry.getKey());
            }
        }
        
        // Remove those matches
        for (String matchId : matchesToRemove) {
            matchMetadata.remove(matchId);
            matchSubscriptions.remove(matchId);
            logger.info("Removed waiting match {} created by player {}", matchId, playerId);
        }
        
        // Remove player from all match subscriptions
        for (Set<String> subscribers : matchSubscriptions.values()) {
            subscribers.remove(playerId);
        }
        
        // Clear any active socket connections
        activeSockets.remove(playerId);
        
        // Clear WebSocket sessions if handler is available
        if (gameWebSocketHandler != null) {
            gameWebSocketHandler.clearPlayerSessions(playerId);
        }
        
        logger.info("Cleared player {} from all active matches", playerId);
    }
    
    private void broadcastMatchStart(String matchId, GameModel game) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "MATCH_START");
        event.put("gameId", game.getId());
        event.put("gameState", game.getGameState());
        event.put("currentPlayerId", game.getCurrentPlayerId());
        broadcastEvent(matchId, event);
    }
    
    private void broadcastEvent(String matchId, Map<String, Object> event) {
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            Set<String> subscribers = matchSubscriptions.get(matchId);
            
            if (subscribers != null) {
                // In a real implementation, we would broadcast through WebSocket
                logger.debug("Broadcasting event to match {}: {}", matchId, eventJson);
            }
        } catch (Exception e) {
            logger.error("Failed to broadcast event to match {}", matchId, e);
        }
    }
    
    // Inner class to store match metadata
    public static class MatchMetadata {
        public String matchId;
        public String creatorId;
        public String status;
        public Instant createdAt;
        public String gameId; // Set when game is created
    }
}