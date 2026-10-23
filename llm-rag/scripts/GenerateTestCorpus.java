import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/**
 * Genere les PDF de test de docs_rag/ a partir des sources Markdown de scripts/test-corpus/.
 *
 * Documents fictifs : leurs faits sont inconnus de tout LLM, ce qui rend visible l'apport du RAG,
 * et la verite terrain (page de chaque reponse) est connue exactement. Chaque section commence
 * sur une nouvelle page et porte un signet PDF, comme dans un vrai document structure.
 *
 * Lancer depuis llm-rag/ : voir scripts/README.md.
 */
public class GenerateTestCorpus {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final float MARGIN = 60;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final String DISCLAIMER =
            "Document fictif créé pour tester un système RAG. Les noms, chiffres et règles sont inventés.";

    record Section(String title, List<String> paragraphs) {
    }

    record Source(String title, String subtitle, List<Section> sections) {
    }

    public static void main(String[] args) throws IOException {
        Path sources = Path.of(args.length > 0 ? args[0] : "scripts/test-corpus");
        Path target = Path.of(args.length > 1 ? args[1] : "docs_rag");
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(sources)) {
            for (Path md : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                Path pdf = target.resolve(md.getFileName().toString().replace(".md", ".pdf"));
                List<Integer> pages = write(parse(md), pdf);
                System.out.println(pdf.getFileName() + " : sections aux pages " + pages);
            }
        }
    }

    static Source parse(Path md) throws IOException {
        String title = null;
        String subtitle = null;
        List<Section> sections = new ArrayList<>();
        for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("## ")) {
                sections.add(new Section(text.substring(3), new ArrayList<>()));
            } else if (text.startsWith("# ")) {
                title = text.substring(2);
            } else if (sections.isEmpty()) {
                subtitle = text;
            } else {
                sections.get(sections.size() - 1).paragraphs().add(text);
            }
        }
        return new Source(title, subtitle, sections);
    }

    /** @return la premiere page de chaque section, dans l'ordre */
    static List<Integer> write(Source source, Path pdf) throws IOException {
        try (PDDocument document = new PDDocument()) {
            Writer writer = new Writer(document, source.title());
            // Couverture : titre, sous-titre, avertissement, sommaire (pages calculees : une section par page).
            writer.newPage();
            writer.text(source.title(), BOLD, 20, 26);
            if (source.subtitle() != null) {
                writer.text(source.subtitle(), REGULAR, 12, 18);
            }
            writer.gap(10);
            writer.text(DISCLAIMER, REGULAR, 10, 14);
            writer.gap(20);
            writer.text("Sommaire", BOLD, 14, 20);
            for (int i = 0; i < source.sections().size(); i++) {
                writer.text((i + 1) + ". " + source.sections().get(i).title() + " .... page " + (i + 2), REGULAR, 11, 16);
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < source.sections().size(); i++) {
                Section section = source.sections().get(i);
                PDPage first = writer.newPage();
                starts.add(document.getPages().indexOf(first) + 1);
                writer.text((i + 1) + ". " + section.title(), BOLD, 15, 22);
                writer.gap(6);
                for (String paragraph : section.paragraphs()) {
                    writer.text(paragraph, REGULAR, 11, 15);
                    writer.gap(6);
                }
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(section.title());
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(first);
                item.setDestination(destination);
                outline.addLast(item);
            }
            writer.close();
            document.save(pdf.toFile());
            return starts;
        }
    }

    /** Ecriture ligne a ligne avec retour automatique et changement de page. */
    static final class Writer {
        private final PDDocument document;
        private final String header;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document, String header) {
            this.document = document;
            this.header = header;
        }

        PDPage newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            int number = document.getNumberOfPages();
            line(header, REGULAR, 8, MARGIN, PDRectangle.A4.getHeight() - 30);
            line("Page " + number, REGULAR, 8, MARGIN, 30);
            y = PDRectangle.A4.getHeight() - MARGIN - 20;
            return page;
        }

        void text(String text, PDType1Font font, float size, float leading) throws IOException {
            for (String wrapped : wrap(text, font, size)) {
                if (y < MARGIN + leading) {
                    newPage();
                }
                line(wrapped, font, size, MARGIN, y);
                y -= leading;
            }
        }

        void gap(float points) {
            y -= points;
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void line(String text, PDType1Font font, float size, float x, float atY) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, atY);
            stream.showText(text);
            stream.endText();
        }

        private static List<String> wrap(String text, PDType1Font font, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > WIDTH && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }
    }
}
