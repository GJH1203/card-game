package com.cardgame.repository;

import com.cardgame.model.GameModel;
import com.cardgame.model.GameMode;
import com.cardgame.model.GameState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

public interface GameRepository extends MongoRepository<GameModel, String> {
    /**
     * Find active games (not completed) for a specific player
     * @param playerId The player ID to search for
     * @param states List of game states considered "active"
     * @return List of active games containing this player
     */
    List<GameModel> findByPlayerIdsContainingAndGameStateIn(String playerId, List<GameState> states);
    
    /**
     * Find the most recent active game for a player
     * @param playerId The player ID to search for
     * @param states List of game states considered "active"
     * @return The most recent active game or empty
     */
    Optional<GameModel> findFirstByPlayerIdsContainingAndGameStateInOrderByUpdatedAtDesc(
        String playerId, List<GameState> states);
        
    /**
     * Find all games for a player
     * @param playerId The player ID to search for
     * @return List of all games containing this player
     */
    List<GameModel> findByPlayerIdsContaining(String playerId);
    
    /**
     * Find the most recent active ONLINE game for a player
     * @param playerId The player ID to search for
     * @param states List of game states considered "active"
     * @param gameMode The game mode to filter by
     * @return The most recent active online game or empty
     */
    Optional<GameModel> findFirstByPlayerIdsContainingAndGameStateInAndGameModeOrderByUpdatedAtDesc(
        String playerId, List<GameState> states, GameMode gameMode);
    
    /**
     * Find all games with specific states
     * @param states List of game states to search for
     * @return List of games matching the given states
     */
    List<GameModel> findByGameStateIn(List<GameState> states);
    
    /**
     * Find all games by a single game state
     * @param state The game state to search for
     * @return List of games matching the given state
     */
    List<GameModel> findByGameState(GameState state);
}
