package io.github.aigoodle.knowledge.reader;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.reader.model.BlockType;
import io.github.aigoodle.knowledge.reader.model.DocumentBlock;
import io.github.aigoodle.knowledge.reader.model.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Page-aware PDF parser. Scanned pages are reported for optional OCR enrichment. */
public class PdfDocumentReader implements DocumentReader {
    public static final String NAME = "pdf";
    @Override public String getName() { return NAME; }
    @Override public boolean supports(String filename) { return filename != null && filename.toLowerCase().endsWith(".pdf"); }
    @Override public String read(byte[] bytes, String filename) { return parse(bytes, filename).text(); }

    @Override
    public ParsedDocument parse(byte[] bytes, String filename) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            List<DocumentBlock> blocks = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdf).strip();
                if (pageText.isBlank()) {
                    warnings.add("Page " + page + " has no text layer; configure an OCR DocumentEnricher");
                    ParserSupport.add(blocks, BlockType.IMAGE, "[Scanned page " + page + "]", page,
                            null, null, Map.of("ocrRequired", true));
                    continue;
                }
                for (String paragraph : pageText.split("(?:\\R\\s*){2,}")) {
                    ParserSupport.add(blocks, BlockType.PARAGRAPH, paragraph, page, null, null, Map.of());
                }
            }
            String title = pdf.getDocumentInformation().getTitle();
            return ParsedDocument.builder().filename(filename).parser(NAME).mediaType("application/pdf")
                    .title(title).blocks(blocks).warnings(warnings)
                    .metadata(Map.of("pageCount", pdf.getNumberOfPages())).build();
        } catch (Exception e) {
            throw new AgentException("extract_failed", "Failed to parse PDF " + filename, e);
        }
    }
}
