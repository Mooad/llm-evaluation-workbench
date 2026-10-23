package com.aimastery.rag.ingestion.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.rag.ingestion.chunking.TokenChunker;
import com.aimastery.rag.ingestion.source.pdf.PdfDocumentReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFormatsTest {

    private final SourceFormat pdf = new SourceFormat("pdf", new PdfDocumentReader(), new TokenChunker(400, 80));

    @Test
    void le_format_est_choisi_sur_le_contenu_pas_sur_le_nom() {
        SourceFormats formats = new SourceFormats(List.of(pdf));

        assertThat(formats.resolve(file("rapport.docx", "%PDF-1.7 ...")).type()).isEqualTo("pdf");
        assertThatThrownBy(() -> formats.resolve(file("notice.pdf", "<html>")))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("notice.pdf")
                .hasMessageContaining("pdf");
    }

    @Test
    void le_premier_format_qui_reconnait_le_fichier_l_emporte() {
        DocumentReader everything = new DocumentReader() {
            @Override
            public boolean supports(SourceFile file) {
                return true;
            }

            @Override
            public ExtractedDocument read(SourceFile file) {
                return new ExtractedDocument(List.of());
            }
        };
        SourceFormat text = new SourceFormat("text", everything, new TokenChunker(400, 80));

        assertThat(new SourceFormats(List.of(pdf, text)).resolve(file("a.pdf", "%PDF-1.7")).type()).isEqualTo("pdf");
        assertThat(new SourceFormats(List.of(text, pdf)).resolve(file("a.pdf", "%PDF-1.7")).type()).isEqualTo("text");
    }

    @Test
    void deux_formats_du_meme_nom_sont_refuses() {
        assertThatThrownBy(() -> new SourceFormats(List.of(pdf, pdf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdf");
    }

    private static SourceFile file(String name, String content) {
        return new SourceFile(name, content.getBytes(StandardCharsets.ISO_8859_1));
    }
}
