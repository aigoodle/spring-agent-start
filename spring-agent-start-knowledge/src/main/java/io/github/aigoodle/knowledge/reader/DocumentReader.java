package io.github.aigoodle.knowledge.reader;

/**
 * SPI that turns a raw byte payload into extracted text. Third parties can publish
 * a new reader (e.g. for a proprietary file format) as a Spring bean and it will be
 * picked up by {@link DocumentReaderRegistry} automatically.
 * <p>
 * The name is matched against the incoming source type / file extension by
 * {@link DocumentReaderRegistry#pick(String)}.
 */
public interface DocumentReader {

    /**
     * Unique reader name, also used as the source type when calling
     * {@link io.github.aigoodle.knowledge.service.KnowledgeService}
     * (e.g. {@code text}, {@code markdown}, {@code pdf}, {@code html}, {@code tika}).
     */
    String getName();

    /** True if this reader can handle the given filename (typically by extension). */
    boolean supports(String filename);

    /** Extract plain text from the payload. */
    String read(byte[] bytes, String filename);
}
