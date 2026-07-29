package io.github.aigoodle.knowledge.chunk.template;

import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.chunk.Chunker;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.enums.ChunkingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects question/answer pairs (English and Chinese markers) and emits one chunk
 * per pair, with the question kept in metadata so it can be weighted at query time.
 * Falls back to {@link NaiveChunker} when no Q/A markers are present.
 */
public class QaChunker implements Chunker {

    private static final Pattern Q = Pattern.compile(
            "^\\s*(?:Q[:：.\\d]|问[:：]|问题[:：]|Question[:：])\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern A = Pattern.compile(
            "^\\s*(?:A[:：.]|答[:：]|答案[:：]|Answer[:：])\\s*", Pattern.CASE_INSENSITIVE);

    private final NaiveChunker fallback = new NaiveChunker();

    @Override
    public ChunkingTemplate template() {
        return ChunkingTemplate.QA;
    }

    @Override
    public List<Chunk> chunk(String text, ProcessRule rule, Map<String, Object> baseMetadata) {
        String[] lines = text.split("\n");
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder question = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        boolean inAnswer = false;
        int pos = 0;
        boolean foundAny = false;

        for (String line : lines) {
            Matcher qm = Q.matcher(line);
            Matcher am = A.matcher(line);
            if (qm.find()) {
                pos = emit(chunks, question, answer, baseMetadata, pos);
                question.setLength(0);
                answer.setLength(0);
                question.append(line.substring(qm.end()).strip());
                inAnswer = false;
                foundAny = true;
            } else if (am.find()) {
                answer.append(line.substring(am.end()).strip());
                inAnswer = true;
            } else if (!line.isBlank()) {
                (inAnswer ? answer : question).append(inAnswer ? " " : " ").append(line.strip());
            }
        }
        emit(chunks, question, answer, baseMetadata, pos);

        if (!foundAny) {
            return fallback.chunk(text, rule, baseMetadata);
        }
        return chunks;
    }

    private int emit(List<Chunk> chunks, StringBuilder question, StringBuilder answer,
                     Map<String, Object> baseMetadata, int pos) {
        String q = question.toString().strip();
        String a = answer.toString().strip();
        if (q.isEmpty() && a.isEmpty()) {
            return pos;
        }
        Map<String, Object> md = new HashMap<>(baseMetadata);
        if (!q.isEmpty()) {
            md.put("question", q);
        }
        String content = q.isEmpty() ? a : (a.isEmpty() ? q : "Q: " + q + "\nA: " + a);
        chunks.add(new Chunk(content, pos, md));
        return pos + 1;
    }
}
