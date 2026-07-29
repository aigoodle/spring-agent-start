package io.github.aigoodle.knowledge.chunk;

import io.github.aigoodle.knowledge.config.ProcessRule;

import java.util.regex.Pattern;

/**
 * Pre-processing cleanup applied before chunking, mirroring Dify's process rules.
 */
public final class TextCleaner {

    private static final Pattern MULTI_WS = Pattern.compile("[ \\t\\x0B\\f]+");
    private static final Pattern MULTI_NL = Pattern.compile("\\n{3,}");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private TextCleaner() {
    }

    public static String clean(String text, ProcessRule rule) {
        if (text == null) {
            return "";
        }
        String out = text.replace("\r\n", "\n").replace("\r", "\n");
        if (rule.isRemoveUrlsEmails()) {
            out = URL.matcher(out).replaceAll(" ");
            out = EMAIL.matcher(out).replaceAll(" ");
        }
        if (rule.isRemoveExtraWhitespace()) {
            out = MULTI_WS.matcher(out).replaceAll(" ");
            out = MULTI_NL.matcher(out).replaceAll("\n\n");
        }
        return out.strip();
    }
}
