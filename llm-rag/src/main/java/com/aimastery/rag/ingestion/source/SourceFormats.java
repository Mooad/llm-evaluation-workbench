package com.aimastery.rag.ingestion.source;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Les formats connus, et le choix du format d'un fichier.
 *
 * Le premier reader qui reconnait le contenu l'emporte : l'ordre de declaration compte,
 * du plus specifique au plus general (un futur reader "texte brut" accepterait presque tout).
 */
public class SourceFormats {

    private final List<SourceFormat> formats;

    public SourceFormats(List<SourceFormat> formats) {
        Set<String> types = new HashSet<>();
        for (SourceFormat format : formats) {
            // Le type est ecrit dans Qdrant et dans le registre : deux formats du meme nom seraient indiscernables.
            if (!types.add(format.type())) {
                throw new IllegalArgumentException("Format declare deux fois : " + format.type());
            }
        }
        this.formats = List.copyOf(formats);
    }

    public SourceFormat resolve(SourceFile file) {
        return formats.stream()
                .filter(format -> format.reader().supports(file))
                .findFirst()
                .orElseThrow(() -> new UnsupportedDocumentException(file.fileName(), types()));
    }

    public List<String> types() {
        return formats.stream().map(SourceFormat::type).toList();
    }
}
