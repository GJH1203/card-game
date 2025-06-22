package com.cardgame.scheduled;

import com.cardgame.model.GameModel;
import com.cardgame.model.GameState;
import com.cardgame.repository.GameRepository;
import com.cardgame.service.nakama.NakamaMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

@Component
public class GameCleanupScheduler {
    private static final Logger logger = LoggerFactory.getLogger(GameCleanupScheduler.class);
    
    @Autowired
    private GameRepository gameRepository;
    
    @Autowired
    private NakamaMatchService nakamaMatchService;
    
    /**
     * Run cleanup every 10 minutes to mark abandoned games as completed
     */
    @Scheduled(fixedDelay = 600000) // 10 minutes in milliseconds
    public void cleanupAbandonedGames() {
        logger.info("Starting scheduled cleanup of abandoned games");
        
        try {
            // Find all games in INITIALIZED or IN_PROGRESS state
            List<GameState> activeStates = Arrays.asList(
                GameState.INITIALIZED, 
                GameState.IN_PROGRESS
            );
            
            List<GameModel> activeGames = gameRepository.findAll().stream()
                .filter(game -> activeStates.contains(game.getGameState()))
                .collect(java.util.stream.Collectors.toList());
            
            // Games older than 30 minutes without updates are considered abandoned
            Instant thirtyMinutesAgo = Instant.now().minus(30, ChronoUnit.MINUTES);
            int cleanedCount = 0;
            
            for (GameModel game : activeGames) {
                // Check if game is abandoned
                if (game.getUpdatedAt() == null || game.getUpdatedAt().isBefore(thirtyMinutesAgo)) {
                    logger.info("Cleaning up abandoned game {} (state: {}, last updated: {})", 
                        game.getId(), game.getGameState(), game.getUpdatedAt());
                    
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
            
            if (cleanedCount > 0) {
                logger.info("Scheduled cleanup completed: cleaned up {} abandoned games out of {} active games", 
                    cleanedCount, activeGames.size());
            } else {
                logger.debug("Scheduled cleanup completed: no abandoned games found");
            }
            
        } catch (Exception e) {
            logger.error("Error during scheduled game cleanup", e);
        }
    }
    
    /**
     * Run a more aggressive cleanup once per hour for very old games
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour in milliseconds
    public void cleanupOldGames() {
        logger.info("Starting scheduled cleanup of old games");
        
        try {
            // Find all non-completed games
            List<GameState> activeStates = Arrays.asList(
                GameState.INITIALIZED, 
                GameState.IN_PROGRESS
            );
            
            List<GameModel> activeGames = gameRepository.findAll().stream()
                .filter(game -> activeStates.contains(game.getGameState()))
                .collect(java.util.stream.Collectors.toList());
            
            // Games older than 2 hours are definitely abandoned
            Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
            int cleanedCount = 0;
            
            for (GameModel game : activeGames) {
                Instant lastUpdate = game.getUpdatedAt() != null ? game.getUpdatedAt() : game.getCreatedAt();
                
                if (lastUpdate == null || lastUpdate.isBefore(twoHoursAgo)) {
                    logger.warn("Cleaning up very old game {} (state: {}, last updated: {})", 
                        game.getId(), game.getGameState(), lastUpdate);
                    
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
            
            if (cleanedCount > 0) {
                logger.info("Old game cleanup completed: cleaned up {} very old games", cleanedCount);
            }
            
        } catch (Exception e) {
            logger.error("Error during old game cleanup", e);
        }
    }
}