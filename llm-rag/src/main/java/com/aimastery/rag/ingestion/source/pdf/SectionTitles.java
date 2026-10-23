package com.aimastery.rag.ingestion.source.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

/**
 * Titre de section de chaque page, lu dans le sommaire du PDF (signets).
 *
 * Une page appartient au dernier signet qui commence a cette page ou avant. A page egale, le
 * signet le plus profond l'emporte : "Pressions de gonflage" plutot que le chapitre "Entretien".
 * Sans sommaire, aucune page n'a de titre : on n'invente pas de structure a partir de la mise en
 * page, trop variable d'un document a l'autre pour etre fiable.
 */
final class SectionTitles {

    private final TreeMap<Integer, String> byStartPage;

    private SectionTitles(TreeMap<Integer, String> byStartPage) {
        this.byStartPage = byStartPage;
    }

    static SectionTitles of(PDDocument document) {
        TreeMap<Integer, String> starts = new TreeMap<>();
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            // Parcours en profondeur : un enfant passe apres son parent, donc l'ecrase a page egale.
            for (Entry entry : flatten(document, outline)) {
                starts.put(entry.page(), entry.title());
            }
        }
        return new SectionTitles(starts);
    }

    /** @return null si aucune section ne commence a cette page ou avant */
    String at(int page) {
        var entry = byStartPage.floorEntry(page);
        return entry == null ? null : entry.getValue();
    }

    private record Entry(int page, String title) {
    }

    private static List<Entry> flatten(PDDocument document, PDOutlineNode node) {
        List<Entry> entries = new ArrayList<>();
        for (PDOutlineItem item : node.children()) {
            Integer page = pageOf(document, item);
            String title = clean(item.getTitle());
            if (page != null && !title.isEmpty()) {
                entries.add(new Entry(page, title));
            }
            entries.addAll(flatten(document, item));
        }
        return entries;
    }

    /** Un signet casse (destination nommee absente, page supprimee) est ignore plutot que de faire echouer la lecture. */
    private static Integer pageOf(PDDocument document, PDOutlineItem item) {
        try {
            PDPage page = item.findDestinationPage(document);
            int index = page == null ? -1 : document.getPages().indexOf(page);
            return index < 0 ? null : index + 1;
        } catch (IOException e) {
            return null;
        }
    }

    private static String clean(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").strip();
    }
}
