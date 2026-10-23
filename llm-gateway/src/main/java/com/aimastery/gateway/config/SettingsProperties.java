package com.aimastery.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param file fichier des defauts globaux par fournisseur, relatif au repertoire de lancement
 */
@ConfigurationProperties(prefix = "gateway.settings")
public record SettingsProperties(String file) {

    public SettingsProperties {
        if (file == null || file.isBlank()) {
            file = "settings/defaults.json";
        }
    }
}
