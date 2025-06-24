package com.cardgame.controller;

import com.cardgame.dto.PlayerAction;
import com.cardgame.dto.online.CreateMatchRequest;
import com.cardgame.dto.online.JoinMatchRequest;
import com.cardgame.dto.online.MatchResponse;
import com.cardgame.model.GameModel;
import com.cardgame.model.GameState;
import com.cardgame.repository.GameRepository;
import com.cardgame.service.nakama.NakamaMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/online-game")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:3001"}, allowCredentials = "true")
public class OnlineGameController {
    private static final Logger logger = LoggerFactory.getLogger(OnlineGameController.class);
    
    @Autowired
    private NakamaMatchService nakamaMatchService;
    
    @Autowired
    private GameRepository gameRepository;
    
    /**
     * Create a new online match
     */
    @PostMapping("/create")
    public CompletableFuture<ResponseEntity<MatchResponse>> createMatch(@RequestBody CreateMatchRequest request) {
        logger.info("Creating online match for player: {}", request.getPlayerId());
        
        return nakamaMatchService.createMatch(request.getPlayerId())
            .thenApply(matchId -> {
                logger.info("Successfully created match with ID: {} for player: {}", matchId, request.getPlayerId());
                MatchResponse response = new MatchResponse();
                response.setMatchId(matchId);
                response.setStatus("WAITING");
                response.setMessage("Match created successfully. Share the code: " + matchId);
                return ResponseEntity.ok(response);
            })
            .exceptionally(ex -> {
                logger.error("Failed to create match for player: {}", request.getPlayerId(), ex);
                MatchResponse errorResponse = new MatchResponse();
                errorResponse.setStatus("ERROR");
                errorResponse.setMessage("Failed to create match: " + ex.getMessage());
                return ResponseEntity.internalServerError().body(errorResponse);
            });
    }
    
    /**
     * Join an existing online match
     */
    @PostMapping("/join/{matchId}")
    public CompletableFuture<ResponseEntity<MatchResponse>> joinMatch(
            @PathVariable String matchId,
            @RequestBody JoinMatchRequest request) {
        logger.info("Player {} joining match {}", request.getPlayerId(), matchId);
        
        // First check if this is a reconnection attempt
        GameModel existingGame = nakamaMatchService.findActiveGameForPlayer(request.getPlayerId());
        if (existingGame != null && existingGame.getNakamaMatchId() != null && 
            existingGame.getNakamaMatchId().contains(matchId)) {
            // This is a reconnection to an existing game
            logger.info("Player {} reconnecting to existing game {}", request.getPlayerId(), existingGame.getId());
            
            MatchResponse response = new MatchResponse();
            response.setMatchId(matchId);
            response.setGameId(existingGame.getId());
            response.setStatus("IN_PROGRESS");
            response.setMessage("Successfully reconnected to match");
            response.setGameState(existingGame.getGameState().toString());
            return CompletableFuture.completedFuture(ResponseEntity.ok(response));
        }
        
        // Otherwise, proceed with normal join
        return nakamaMatchService.joinMatch(request.getPlayerId(), matchId)
            .thenApply(game -> {
                MatchResponse response = new MatchResponse();
                response.setMatchId(matchId);
                response.setGameId(game.getId());
                response.setStatus("IN_PROGRESS");
                response.setMessage("Successfully joined match");
                response.setGameState(game.getGameState().toString());
                return ResponseEntity.ok(response);
            })
            .exceptionally(ex -> {
                logger.error("Failed to join match {}", matchId, ex);
                MatchResponse errorResponse = new MatchResponse();
                errorResponse.setStatus("ERROR");
                
                // Get the root cause
                Throwable rootCause = ex;
                while (rootCause.getCause() != null) {
                    rootCause = rootCause.getCause();
                }
                
                errorResponse.setMessage("Failed to join match: " + rootCause.getMessage());
                return ResponseEntity.badRequest().body(errorResponse);
            });
    }
    
    /**
     * Get the current state of a match
     */
    @GetMapping("/match/{matchId}/state")
    public ResponseEntity<?> getMatchState(@PathVariable String matchId) {
        try {
            GameModel game = nakamaMatchService.getMatchState(matchId);
            return ResponseEntity.ok(game);
        } catch (Exception e) {
            logger.error("Failed to get match state for {}", matchId, e);
            return ResponseEntity.badRequest().body("Match not found");
        }
    }
    
    /**
     * Send a game action in an online match
     */
    @PostMapping("/match/{matchId}/action")
    public CompletableFuture<ResponseEntity<String>> sendAction(
            @PathVariable String matchId,
            @RequestBody PlayerAction action) {
        logger.info("Player {} sending action in match {}", action.getPlayerId(), matchId);
        
        // Convert PlayerAction to Map for NakamaMatchService
        Map<String, Object> actionData = new HashMap<>();
        actionData.put("type", action.getType().toString());
        actionData.put("playerId", action.getPlayerId());
        if (action.getCard() != null) {
            actionData.put("card", action.getCard());
        }
        if (action.getTargetPosition() != null) {
            actionData.put("position", action.getTargetPosition());
        }
        
        return nakamaMatchService.sendGameAction(matchId, actionData)
            .thenApply(v -> ResponseEntity.ok("Action sent successfully"))
            .exceptionally(ex -> {
                logger.error("Failed to send action in match {}", matchId, ex);
                return ResponseEntity.internalServerError().body("Failed to send action: " + ex.getMessage());
            });
    }
    
    /**
     * Handle player disconnection
     */
    @PostMapping("/match/{matchId}/disconnect/{playerId}")
    public ResponseEntity<String> handleDisconnect(
            @PathVariable String matchId,
            @PathVariable String playerId) {
        logger.info("Player {} disconnecting from match {}", playerId, matchId);
        
        try {
            nakamaMatchService.handleDisconnection(playerId, matchId);
            return ResponseEntity.ok("Disconnected successfully");
        } catch (Exception e) {
            logger.error("Failed to handle disconnection", e);
            return ResponseEntity.internalServerError().body("Failed to disconnect: " + e.getMessage());
        }
    }
    
    /**
     * Leave all matches for a player (useful for testing)
     * This endpoint allows a player to cleanly leave all their matches before joining a new one
     */
    @PostMapping("/leave-all/{playerId}")
    public ResponseEntity<Map<String, Object>> leaveAllMatches(@PathVariable String playerId) {
        logger.info("Player {} leaving all matches", playerId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            // First, clear any match metadata
            nakamaMatchService.clearPlayerMatches(playerId);
            
            // Also explicitly check for and log any completed games
            List<GameModel> allPlayerGames = gameRepository.findByPlayerIdsContaining(playerId);
                
            for (GameModel game : allPlayerGames) {
                logger.info("Player {} has game {} in state {}", playerId, game.getId(), game.getGameState());
            }
            
            response.put("success", true);
            response.put("message", "Successfully left all matches");
            response.put("playerId", playerId);
            response.put("totalGames", allPlayerGames.size());
            response.put("completedGames", allPlayerGames.stream()
                .filter(g -> g.getGameState() == GameState.COMPLETED).count());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to leave matches for player {}", playerId, e);
            response.put("success", false);
            response.put("error", "Failed to leave matches: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Check if a player has any active (non-completed) games
     */
    @GetMapping("/active-game/{playerId}")
    public ResponseEntity<Map<String, Object>> getActiveGame(@PathVariable String playerId) {
        logger.info("Checking for active games for player: {}", playerId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            // First, clean up any abandoned games for this player
            int cleanedGames = cleanupAbandonedGamesForPlayer(playerId);
            if (cleanedGames > 0) {
                logger.info("Cleaned up {} abandoned games for player {}", cleanedGames, playerId);
            }
            
            GameModel activeGame = nakamaMatchService.findActiveGameForPlayer(playerId);
            if (activeGame != null) {
                // Double-check that the game is actually active
                if (activeGame.getGameState() == GameState.COMPLETED) {
                    logger.warn("Found completed game {} marked as active for player {}, cleaning up", 
                        activeGame.getId(), playerId);
                    // Clean up any stale match metadata
                    nakamaMatchService.clearPlayerMatches(playerId);
                    response.put("hasActiveGame", false);
                    response.put("message", "No active games found (cleaned up completed game)");
                } else {
                    // Check if this is an abandoned game (no updates in last 30 minutes)
                    Instant thirtyMinutesAgo = Instant.now().minus(30, ChronoUnit.MINUTES);
                    if (activeGame.getUpdatedAt() != null && activeGame.getUpdatedAt().isBefore(thirtyMinutesAgo)) {
                        logger.warn("Found stale IN_PROGRESS game {} (last updated: {}) for player {}, treating as abandoned", 
                            activeGame.getId(), activeGame.getUpdatedAt(), playerId);
                        
                        // Mark the game as completed due to abandonment
                        activeGame.setGameState(GameState.COMPLETED);
                        activeGame.setUpdatedAt(Instant.now());
                        gameRepository.save(activeGame);
                        
                        // Clean up match metadata
                        nakamaMatchService.clearPlayerMatches(playerId);
                        
                        response.put("hasActiveGame", false);
                        response.put("message", "No active games found (cleaned up abandoned game)");
                    } else {
                        // Check if opponent exists (games with null opponent are invalid)
                        String opponentId = activeGame.getPlayerIds().stream()
                            .filter(id -> !id.equals(playerId))
                            .findFirst()
                            .orElse(null);
                            
                        if (opponentId == null) {
                            logger.warn("Active game {} has no opponent for player {}, treating as invalid", 
                                activeGame.getId(), playerId);
                            
                            // Mark invalid game as completed
                            activeGame.setGameState(GameState.COMPLETED);
                            activeGame.setUpdatedAt(Instant.now());
                            gameRepository.save(activeGame);
                            
                            response.put("hasActiveGame", false);
                            response.put("message", "No active games found (cleaned up invalid game)");
                        } else {
                            // Valid active game
                            response.put("hasActiveGame", true);
                            response.put("gameId", activeGame.getId());
                            response.put("matchId", activeGame.getNakamaMatchId());
                            response.put("gameState", activeGame.getGameState().toString());
                            response.put("isCurrentTurn", activeGame.getCurrentPlayerId().equals(playerId));
                            response.put("opponentId", opponentId);
                        }
                    }
                }
            } else {
                response.put("hasActiveGame", false);
                response.put("message", "No active games found");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to check active games for player {}", playerId, e);
            response.put("error", "Failed to check active games: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Admin endpoint to clean up all abandoned games
     */
    @PostMapping("/admin/cleanup-abandoned-games")
    public ResponseEntity<Map<String, Object>> cleanupAbandonedGames() {
        logger.info("Admin: Cleaning up all abandoned games");
        
        Map<String, Object> response = new HashMap<>();
        try {
            // Find all games in INITIALIZED or IN_PROGRESS state
            List<GameState> activeStates = Arrays.asList(
                GameState.INITIALIZED,
                GameState.IN_PROGRESS
            );
            List<GameModel> activeGames = gameRepository.findByGameStateIn(activeStates);
            
            Instant thirtyMinutesAgo = Instant.now().minus(30, ChronoUnit.MINUTES);
            int cleanedCount = 0;
            
            for (GameModel game : activeGames) {
                // Check if game is abandoned (no updates in last 30 minutes)
                if (game.getUpdatedAt() == null || game.getUpdatedAt().isBefore(thirtyMinutesAgo)) {
                    logger.info("Cleaning up abandoned game {} (last updated: {})", 
                        game.getId(), game.getUpdatedAt());
                    
                    // Mark as completed
                    game.setGameState(GameState.COMPLETED);
                    game.setUpdatedAt(Instant.now());
                    gameRepository.save(game);
                    
                    // Clean up match metadata for all players
                    if (game.getPlayerIds() != null) {
                        for (String playerId : game.getPlayerIds()) {
                            nakamaMatchService.clearPlayerMatches(playerId);
                        }
                    }
                    
                    cleanedCount++;
                }
            }
            
            response.put("success", true);
            response.put("totalActiveGames", activeGames.size());
            response.put("cleanedGames", cleanedCount);
            response.put("message", String.format("Cleaned up %d abandoned games out of %d active games", 
                cleanedCount, activeGames.size()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to cleanup abandoned games", e);
            response.put("success", false);
            response.put("error", "Failed to cleanup abandoned games: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Helper method to clean up abandoned games for a specific player
     */
    private int cleanupAbandonedGamesForPlayer(String playerId) {
        try {
            // Find all non-completed games for this player
            List<GameState> activeStates = Arrays.asList(
                GameState.INITIALIZED, 
                GameState.IN_PROGRESS
            );
            
            List<GameModel> playerGames = gameRepository
                .findByPlayerIdsContainingAndGameStateIn(playerId, activeStates);
            
            Instant thirtyMinutesAgo = Instant.now().minus(30, ChronoUnit.MINUTES);
            int cleanedCount = 0;
            
            for (GameModel game : playerGames) {
                // Check if game is abandoned
                if (game.getUpdatedAt() == null || game.getUpdatedAt().isBefore(thirtyMinutesAgo)) {
                    logger.info("Cleaning up abandoned game {} for player {} (last updated: {})", 
                        game.getId(), playerId, game.getUpdatedAt());
                    
                    // Mark as completed
                    game.setGameState(GameState.COMPLETED);
                    game.setUpdatedAt(Instant.now());
                    gameRepository.save(game);
                    
                    cleanedCount++;
                }
            }
            
            return cleanedCount;
        } catch (Exception e) {
            logger.error("Error cleaning up abandoned games for player {}", playerId, e);
            return 0;
        }
    }
}
