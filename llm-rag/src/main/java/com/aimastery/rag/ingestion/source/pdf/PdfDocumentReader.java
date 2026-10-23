package com.aimastery.rag.ingestion.source.pdf;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.ingestion.source.DocumentReader;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.aimastery.rag.ingestion.source.SourceFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF -> une section par page, avec le titre de la partie du document qui la contient.
 *
 * Une page par section, parce que la page est l'unite que l'on cite a l'utilisateur. Le titre
 * vient du sommaire du PDF (signets) : "Reinitialisation de la version Zigbee" dit au modele, et
 * a l'embedding, de quoi parle une page que son texte seul ne situe pas.
 *
 * PDFBox directement, et non PagePdfDocumentReader de Spring AI : son extracteur "mise en page"
 * echoue sur la notice Dacia ("Unreasonable number of lines (280911)") et fait echouer tout le
 * document. L'extracteur standard de PDFBox, page par page, lit le meme texte sans ce calcul.
 */
public class PdfDocumentReader implements DocumentReader {

    @Override
    public boolean supports(SourceFile file) {
        return file.startsWith("%PDF-");
    }

    @Override
    public ExtractedDocument read(SourceFile file) {
        try (PDDocument document = Loader.loadPDF(file.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Pas de setSortByPosition(true) : trier par position melange les deux colonnes de la
            // notice ligne a ligne ("Vehicules utilises a Pour votre securite..."). L'ordre du flux
            // PDF, lui, donne chaque colonne d'un bloc.

            SectionTitles titles = SectionTitles.of(document);
            List<Section> sections = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                // Pages sans texte (schemas, pages blanches) : rien a vectoriser.
                if (!text.isEmpty()) {
                    sections.add(new Section(text, new PageLocation(page), titles.at(page)));
                }
            }
            return new ExtractedDocument(sections);

        } catch (IOException e) {
            throw new IllegalStateException("PDF illisible : " + e.getMessage(), e);
        }
    }

    /**
     * Espaces multiples et lignes vides n'apportent rien au sens et coutent des tokens :
     * on les retire avant le decoupage, pour que la taille d'un chunk mesure du contenu.
     */
    static String normalize(String text) {
        return Arrays.stream(text.split("\\R"))
                .map(line -> line.replaceAll("[ \\t\\u00A0]{2,}", " ").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
