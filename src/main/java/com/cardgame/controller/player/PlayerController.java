package com.cardgame.controller.player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.cardgame.dto.PlayerDto;
import com.cardgame.model.Card;
import com.cardgame.model.Player;
import com.cardgame.service.player.PlayerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    // Removed auto-creation endpoint to prevent unauthorized player creation
    // Players should only be created through proper authentication flow

    @GetMapping("/{playerId}")
    public ResponseEntity<PlayerDto> getPlayer(@PathVariable String playerId) {
        return ResponseEntity.ok(playerService.getPlayerDto(playerId));
    }

    @GetMapping("/game/players/{playerId}/hand")
    public ResponseEntity<List<Card>> getPlayerHand(@PathVariable String playerId) {
        Player player = playerService.getPlayer(playerId);
        return ResponseEntity.ok(player.getHand());
    }

    @GetMapping("/by-name/{name}")
    public ResponseEntity<PlayerDto> getPlayerByName(@PathVariable String name) {
        Player player = playerService.findPlayerByName(name);
        if (player == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(playerService.getPlayerDto(player.getId()));
    }

    @GetMapping("/by-supabase-id/{supabaseUserId}")
    public ResponseEntity<com.cardgame.dto.PlayerResponse> getPlayerBySupabaseId(@PathVariable String supabaseUserId) {
        Optional<Player> player = playerService.findPlayerBySupabaseUserId(supabaseUserId);
        if (player.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Player foundPlayer = player.get();
        com.cardgame.dto.PlayerResponse response = new com.cardgame.dto.PlayerResponse(
            foundPlayer.getId(),
            foundPlayer.getName(),
            foundPlayer.getEmail(),
            foundPlayer.getSupabaseUserId()
        );
        
        return ResponseEntity.ok(response);
    }

    // Test endpoints removed for production security
    // Use proper authentication flow to create players
    
    @DeleteMapping("/{playerId}")
    public ResponseEntity<String> deletePlayerById(@PathVariable String playerId) {
        try {
            // Check if player exists first
            Player player = playerService.getPlayer(playerId);
            
            // Delete the player
            playerService.deletePlayer(playerId);
            
            return ResponseEntity.ok("Player " + player.getName() + " (ID: " + playerId + ") deleted successfully");
        } catch (com.cardgame.exception.player.PlayerNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to delete player: " + e.getMessage());
        }
    }

    @DeleteMapping("/all")
    public ResponseEntity<String> deleteAllPlayers() {
        try {
            List<Player> allPlayers = playerService.getAllPlayers();
            int playerCount = allPlayers.size();
            
            // Delete all players in batch for better performance
            playerService.deleteAllPlayers();
            
            return ResponseEntity.ok("Deleted " + playerCount + " players successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to delete players: " + e.getMessage());
        }
    }
}
