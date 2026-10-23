package com.aimastery.gateway.api;

import com.aimastery.gateway.api.dto.GenerationDefaultsRequest;
import com.aimastery.gateway.api.dto.PricingView;
import com.aimastery.gateway.api.dto.ProviderSettings;
import com.aimastery.gateway.api.dto.SettingsView;
import com.aimastery.gateway.config.FileGenerationDefaults;
import com.aimastery.gateway.config.PricingProperties;
import com.aimastery.gateway.core.GenerationDefaults;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.ProviderRegistry;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reglages globaux et tarifs, lisibles et modifiables depuis le portail.
 *
 * Aucune authentification : acceptable tant que le Gateway ne sert qu'en local.
 * A proteger avant toute exposition reseau — un PUT change le comportement de tous les clients.
 */
@RestController
public class SettingsController {

    private final FileGenerationDefaults defaults;
    private final ProviderRegistry registry;
    private final PricingProperties pricing;

    public SettingsController(FileGenerationDefaults defaults, ProviderRegistry registry, PricingProperties pricing) {
        this.defaults = defaults;
        this.registry = registry;
        this.pricing = pricing;
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        List<ProviderSettings> providers = registry.available().stream()
                .sorted()
                .map(name -> view(registry.resolve(name)))
                .toList();
        return new SettingsView(defaults.file().toString(), providers);
    }

    @PutMapping("/settings/{provider}")
    public ProviderSettings update(@PathVariable String provider,
                                   @Valid @RequestBody GenerationDefaultsRequest request) {
        LlmProvider target = registry.resolve(provider);
        if (Boolean.TRUE.equals(request.reasoning()) && !target.supportsReasoning()) {
            // Stocker un reglage sans effet ferait croire qu'il s'applique.
            throw new InvalidSettingsException("'" + provider + "' n'a pas de mode raisonnement pilotable");
        }
        defaults.update(target.name(), request.toDomain());
        return view(target);
    }

    @GetMapping("/pricing")
    public PricingView pricing() {
        List<PricingView.ModelPrice> models = pricing.models().entrySet().stream()
                .map(e -> new PricingView.ModelPrice(e.getKey(), e.getValue().inputPer1m(), e.getValue().outputPer1m()))
                .sorted(Comparator.comparing(PricingView.ModelPrice::model))
                .toList();
        return new PricingView(pricing.currency(), "application.yml : gateway.pricing", models);
    }

    private ProviderSettings view(LlmProvider provider) {
        GenerationDefaults values = defaults.forProvider(provider.name());
        return new ProviderSettings(provider.name(), provider.supportsReasoning(), values);
    }
}
