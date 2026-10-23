package com.aimastery.gateway.eval;

import com.aimastery.gateway.structured.SchemaSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Charge un dataset depuis le classpath (classpath:...) ou le disque (file:/chemin/absolu).
 * Un dataset mal forme est refuse avant tout appel : on ne depense pas de tokens
 * pour decouvrir au 7e cas qu'un id est duplique.
 */
@Component
public class EvalDatasetLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper;

    public EvalDatasetLoader(ResourceLoader resourceLoader, ObjectMapper mapper) {
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
    }

    public List<EvalCase> load(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new EvalDatasetException("Dataset introuvable : '" + location
                    + "'. Utilise classpath:evaluation/xxx.json ou file:/chemin/absolu.json");
        }

        List<EvalCase> cases;
        try (InputStream in = resource.getInputStream()) {
            cases = mapper.readValue(in, new TypeReference<List<EvalCase>>() { });
        } catch (IOException e) {
            throw new EvalDatasetException("Dataset illisible (" + location + ") : " + e.getMessage());
        }

        validate(cases, location);
        return cases;
    }

    /** Meme validation qu'au chargement, pour un dataset recu avant d'etre ecrit sur disque. */
    public List<EvalCase> parse(String json, String label) {
        List<EvalCase> cases;
        try {
            cases = mapper.readValue(json, new TypeReference<List<EvalCase>>() { });
        } catch (IOException e) {
            throw new EvalDatasetException("Dataset illisible (" + label + ") : " + e.getMessage());
        }
        validate(cases, label);
        return cases;
    }

    private void validate(List<EvalCase> cases, String location) {
        if (cases == null || cases.isEmpty()) {
            throw new EvalDatasetException("Dataset vide : " + location);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            if (c.id() == null || c.id().isBlank()) {
                throw new EvalDatasetException("Cas n°" + (i + 1) + " sans id");
            }
            if (!seen.add(c.id())) {
                throw new EvalDatasetException("Id duplique : " + c.id());
            }
            if (c.prompt() == null || c.prompt().isBlank()) {
                throw new EvalDatasetException("Cas " + c.id() + " sans prompt");
            }
            if (c.hasSchema()) {
                List<String> problems = SchemaSupport.problems(c.schema());
                if (!problems.isEmpty()) {
                    throw new EvalDatasetException("Cas " + c.id() + " : schema non gere : "
                            + String.join(" ; ", problems));
                }
            }
        }
    }
}
