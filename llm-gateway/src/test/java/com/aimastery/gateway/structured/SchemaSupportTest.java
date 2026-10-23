package com.aimastery.gateway.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderRegistry;
import com.aimastery.gateway.core.Usage;
import com.aimastery.gateway.observability.CallLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode schema(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void refuse_pattern_en_nommant_le_mot_cle_et_son_chemin() throws Exception {
        JsonNode schema = schema("""
                {"type": "object", "properties": {"email": {"type": "string", "pattern": ".+@.+"}}}""");

        assertThat(SchemaSupport.problems(schema))
                .containsExactly("$.properties.email : mot-cle 'pattern' non gere");
    }

    @Test
    void refuse_minItems_dans_un_tableau_imbrique() throws Exception {
        JsonNode schema = schema("""
                {"type": "object", "properties": {"tracks": {"type": "array", "minItems": 1,
                  "items": {"type": "object", "properties": {"lang": {"type": "string", "format": "iso"}}}}}}""");

        assertThat(SchemaSupport.problems(schema)).containsExactly(
                "$.properties.tracks : mot-cle 'minItems' non gere",
                "$.properties.tracks.items.properties.lang : mot-cle 'format' non gere");
    }

    @Test
    void accepte_les_mots_cles_purement_descriptifs() throws Exception {
        JsonNode schema = schema("""
                {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": "Ticket",
                 "description": "Classement d'un incident", "type": "object",
                 "properties": {"severity": {"type": "string", "enum": ["low", "high"],
                   "description": "gravite", "examples": ["low"]}}}""");

        assertThat(SchemaSupport.problems(schema)).isEmpty();
    }

    @Test
    void refuse_les_formes_que_le_validateur_ignorerait() throws Exception {
        JsonNode schema = schema("""
                {"type": ["object", "null"], "additionalProperties": {"type": "string"}}""");

        assertThat(SchemaSupport.problems(schema)).hasSize(2)
                .anyMatch(p -> p.contains("type"))
                .anyMatch(p -> p.contains("additionalProperties"));
    }

    @Test
    void aucun_appel_au_fournisseur_sur_un_schema_refuse() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GatewayProperties properties = new GatewayProperties("fake", Map.of(
                "fake", new GatewayProperties.ProviderConfig("http://localhost", "m", "", 5)));
        StructuredChatService service = new StructuredChatService(
                new ChatService(new ProviderRegistry(List.of(provider), properties), new CallLogger()),
                new SchemaPromptBuilder(), new SchemaValidator());

        assertThatThrownBy(() -> service.chat(null, null, List.of(Message.user("x")),
                schema("{\"type\": \"string\", \"pattern\": \"^a\"}"), ChatOptions.defaults()))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("pattern");
        assertThat(provider.calls).isZero();
    }

    private static final class RecordingProvider implements LlmProvider {
        private int calls;

        @Override public String name() { return "fake"; }
        @Override public String defaultModel() { return "m"; }
        @Override public ChatResult chat(ChatCall call) {
            calls++;
            return ChatResult.of("{}", "m", new Usage(1, 1), "stop");
        }
    }
}
