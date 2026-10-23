package com.aimastery.rag.ingestion.source.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.aimastery.rag.ingestion.source.SourceFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.StreamSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.junit.jupiter.api.Test;

class PdfDocumentReaderTest {

    private final PdfDocumentReader reader = new PdfDocumentReader();

    @Test
    void chaque_page_porte_le_titre_de_sa_section_lu_dans_le_sommaire() throws IOException {
        ExtractedDocument document = reader.read(new SourceFile("kelvio.pdf",
                pdf(5, outline -> {
                    outline.root().addLast(item("Réinitialisation de la version Wi-Fi (T3-W)", outline.page(4)));
                    outline.root().addLast(item("Réinitialisation de la version Zigbee (T3-Z)", outline.page(5)));
                })));

        assertThat(page(document, 5).title()).isEqualTo("Réinitialisation de la version Zigbee (T3-Z)");
        assertThat(page(document, 4).title()).isEqualTo("Réinitialisation de la version Wi-Fi (T3-W)");
        // La couverture precede le premier signet : aucune section.
        assertThat(page(document, 1).title()).isNull();
    }

    @Test
    void a_page_egale_le_signet_le_plus_profond_l_emporte_et_vaut_jusqu_a_la_section_suivante() throws IOException {
        byte[] pdf = pdf(3, outline -> {
            PDOutlineItem chapter = item("Entretien", outline.page(1));
            chapter.addLast(item("Pressions de gonflage", outline.page(2)));
            outline.root().addLast(chapter);
        });

        ExtractedDocument document = reader.read(new SourceFile("doc.pdf", pdf));

        assertThat(document.sections()).extracting(Section::title)
                .containsExactly("Entretien", "Pressions de gonflage", "Pressions de gonflage");
    }

    @Test
    void sans_sommaire_aucune_section_n_est_inventee() throws IOException {
        ExtractedDocument document = reader.read(new SourceFile("doc.pdf", pdf(2, outline -> { })));

        assertThat(document.sections()).extracting(Section::title).containsOnlyNulls();
    }

    private static Section page(ExtractedDocument document, int page) {
        return document.sections().stream().filter(s -> s.location().equals(new PageLocation(page))).findFirst().orElseThrow();
    }

    private static PDOutlineItem item(String title, PDPage page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(page);
        item.setDestination(destination);
        return item;
    }

    interface OutlineBuilder {
        void build(Outline outline);
    }

    record Outline(PDOutlineNode root, List<PDPage> pages) {
        PDPage page(int number) {
            return pages.get(number - 1);
        }
    }

    /** Un PDF de N pages de texte, avec le sommaire que construit le test. */
    private static byte[] pdf(int pages, OutlineBuilder builder) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText("Texte de la page " + i);
                    stream.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            builder.build(new Outline(outline, StreamSupport.stream(document.getPages().spliterator(), false).toList()));
            if (outline.hasChildren()) {
                document.getDocumentCatalog().setDocumentOutline(outline);
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
