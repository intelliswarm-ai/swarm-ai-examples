package ai.intelliswarm.swarmai.evolution;

import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the self-evolution timeline, visible in Studio.
 *
 * <p>Shows how swarms have restructured themselves over time using
 * existing framework capabilities (INTERNAL observations → EvolutionEngine).
 *
 * <p>GET /api/studio/evolutions — returns the evolution history with
 * before/after topology snapshots.
 */
@RestController
@RequestMapping("/api/studio")
public class EvolutionTimelineController {

    private final LedgerStore ledgerStore;

    @Autowired
    public EvolutionTimelineController(@Autowired(required = false) LedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @GetMapping("/evolutions")
    public ResponseEntity<Map<String, Object>> getEvolutionTimeline(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (ledgerStore == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Self-improvement ledger is not enabled. Add a DataSource to enable evolution tracking."
            ));
        }

        List<LedgerStore.StoredEvolution> evolutions = ledgerStore.getRecentEvolutions(limit);
        List<Map<String, Object>> timeline = evolutions.stream()
                .map(evo -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("swarmId", evo.swarmId());
                    entry.put("type", evo.evolutionType());
                    entry.put("reason", evo.reason());
                    entry.put("before", evo.beforeJson());
                    entry.put("after", evo.afterJson());
                    entry.put("timestamp", evo.createdAt().toString());
                    return entry;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "available", true,
                "totalEvolutions", timeline.size(),
                "timeline", timeline
        ));
    }
}
