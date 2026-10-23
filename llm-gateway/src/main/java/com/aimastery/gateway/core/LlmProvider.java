package com.aimastery.gateway.core;

import java.util.function.Consumer;

/**
 * Le port. Tout fournisseur LLM entre dans le systeme par cette interface, et
 * uniquement par elle. Ajouter un fournisseur = ajouter une implementation,
 * sans toucher au controller ni au service.
 */
public interface LlmProvider {

    /** Identifiant utilise en configuration et dans les requetes : "ollama", "deepseek". */
    String name();

    /** Modele utilise quand la requete n'en precise aucun. */
    String defaultModel();

    ChatResult chat(ChatCall call);

    /**
     * Generation en flux : chaque fragment de texte part dans onDelta des son arrivee ;
     * le resultat final (usage, finish reason) est renvoye a la fin.
     *
     * Defaut : un seul fragment, la reponse complete. Un adaptateur sans streaming
     * reste utilisable sur /chat/stream, simplement sans le gain de latence percue.
     */
    default ChatResult stream(ChatCall call, Consumer<String> onDelta) {
        ChatResult result = chat(call);
        if (result.content() != null && !result.content().isEmpty()) {
            onDelta.accept(result.content());
        }
        return result;
    }

    /**
     * Le fournisseur a-t-il un mode raisonnement pilotable (ChatOptions.reasoning) ?
     * Sert a ne proposer le reglage que la ou il a un effet.
     */
    default boolean supportsReasoning() {
        return false;
    }

    /**
     * Sonde legere : joignable ? modele disponible ? Aucune generation, aucun token.
     *
     * Defaut volontairement sur : un nouvel adaptateur sans sonde repond UNKNOWN
     * plutot que de faire croire a une panne.
     */
    default ProviderHealth health() {
        return ProviderHealth.unknown(name(), defaultModel(), "aucune sonde implementee pour ce fournisseur");
    }
}
