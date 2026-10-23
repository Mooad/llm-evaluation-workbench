package com.aimastery.gateway.api.dto;

import java.util.List;

/** @param file ou ces defauts sont stockes : ce que le portail modifie, c'est ce fichier */
public record SettingsView(String file, List<ProviderSettings> providers) {
}
