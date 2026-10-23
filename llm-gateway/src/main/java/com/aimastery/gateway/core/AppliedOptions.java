package com.aimastery.gateway.core;

/**
 * Reglages reellement envoyes au fournisseur, chacun avec son origine.
 *
 * Sans l'origine, une reponse tronquee ne dit pas si maxTokens venait de l'appelant
 * ou d'un defaut global oublie dans settings/defaults.json.
 */
public record AppliedOptions(Setting<Boolean> reasoning,
                             Setting<Integer> maxTokens,
                             Setting<Double> temperature,
                             Double topP) {

    public enum Origin {
        /** Precise par l'appelant. */
        REQUEST,
        /** Defaut global du Gateway pour ce fournisseur. */
        GLOBAL,
        /** Rien d'impose : le fournisseur applique son propre defaut. */
        PROVIDER
    }

    public record Setting<T>(T value, Origin origin) {

        static <T> Setting<T> resolve(T requested, T global) {
            if (requested != null) {
                return new Setting<>(requested, Origin.REQUEST);
            }
            if (global != null) {
                return new Setting<>(global, Origin.GLOBAL);
            }
            return new Setting<>(null, Origin.PROVIDER);
        }
    }

    /** La requete prime sur le global, le global sur le defaut du fournisseur. */
    public static AppliedOptions resolve(ChatOptions requested, GenerationDefaults defaults) {
        ChatOptions r = requested == null ? ChatOptions.defaults() : requested;
        GenerationDefaults d = defaults == null ? GenerationDefaults.NONE : defaults;
        return new AppliedOptions(
                Setting.resolve(r.reasoning(), d.reasoning()),
                Setting.resolve(r.maxTokens(), d.maxTokens()),
                Setting.resolve(r.temperature(), d.temperature()),
                r.topP());
    }

    public ChatOptions toChatOptions() {
        return new ChatOptions(temperature.value(), topP, maxTokens.value(), reasoning.value());
    }
}
