package com.example.shop.chaos;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control API used by the /chaos/ page, the chaosctl CLI and the sidecars (which poll it).
 * Spans for /chaos/** are dropped in the collector so they never show up in APM.
 */
@RestController
@RequestMapping("/chaos/api")
public class ChaosController {

    public record ScenarioView(String id, String title, String description, String intensityHint,
                               boolean enabled, int intensity) {}

    public record Update(Boolean enabled, Integer intensity) {}

    private final ChaosState state;

    public ChaosController(ChaosState state) {
        this.state = state;
    }

    @GetMapping("/scenarios")
    public List<ScenarioView> list() {
        return state.snapshot().entrySet().stream().map(e -> view(e.getKey(), e.getValue())).toList();
    }

    @GetMapping("/scenarios/{id}")
    public ResponseEntity<ScenarioView> get(@PathVariable String id) {
        return Scenario.fromId(id)
                .map(s -> ResponseEntity.ok(view(s, state.get(s))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/scenarios/{id}")
    public ResponseEntity<ScenarioView> update(@PathVariable String id, @RequestBody Update update) {
        return Scenario.fromId(id)
                .map(s -> {
                    boolean enabled = update.enabled() != null ? update.enabled() : state.enabled(s);
                    state.set(s, enabled, update.intensity());
                    return ResponseEntity.ok(view(s, state.get(s)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        state.reset();
        return Map.of("status", "all scenarios disabled");
    }

    private static ScenarioView view(Scenario s, ChaosState.Setting setting) {
        return new ScenarioView(s.id(), s.title(), s.description(), s.intensityHint(),
                setting.enabled(), setting.intensity());
    }
}
