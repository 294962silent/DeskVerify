package com.echomind.saas.rag.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class MarkdownAwareChunker implements DocumentChunker {

    private static final int MAX_CHARS = 800;
    private static final int MIN_CHARS = 240;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public List<ChunkDraft> chunk(String normalizedText) {
        String text = Objects.requireNonNull(normalizedText, "normalizedText");
        if (text.isBlank()) {
            return List.of();
        }

        List<ChunkDraft> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + MAX_CHARS);
            int end = chooseBoundary(text, start, hardEnd);
            int actualStart = skipLeadingWhitespace(text, start, end);
            int actualEnd = trimTrailingWhitespace(text, actualStart, end);
            if (actualEnd > actualStart) {
                String content = text.substring(actualStart, actualEnd);
                HeadingContext heading = headingContext(text, actualEnd);
                chunks.add(new ChunkDraft(
                        index++,
                        content,
                        null,
                        heading.sectionTitle(),
                        heading.headingPath(),
                        actualStart,
                        actualEnd,
                        sha256(content)));
            }
            if (end >= text.length()) {
                break;
            }
            int chunkLength = end - start;
            int overlap = Math.min(
                    OVERLAP_CHARS,
                    Math.max(0, chunkLength - MIN_CHARS));
            int nextStart = Math.max(start + 1, end - overlap);
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private static int chooseBoundary(String text, int start, int hardEnd) {
        int minimum = Math.min(hardEnd, start + MIN_CHARS);
        int heading = nextHeadingPosition(text, start + 1, hardEnd);
        if (heading > start && !text.substring(start, heading).isBlank()) {
            return heading;
        }
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph >= minimum) {
            return paragraph;
        }
        int line = text.lastIndexOf('\n', hardEnd);
        if (line >= minimum) {
            return line;
        }
        for (int cursor = hardEnd; cursor >= minimum; cursor--) {
            char value = text.charAt(cursor - 1);
            if (value == '。' || value == '！' || value == '？'
                    || value == '.' || value == '!' || value == '?') {
                return cursor;
            }
        }
        return hardEnd;
    }

    private static int nextHeadingPosition(String text, int from, int end) {
        int lineStart = text.indexOf('\n', Math.max(0, from - 1));
        int cursor = lineStart < 0 ? from : lineStart + 1;
        while (cursor < end) {
            int lineEnd = text.indexOf('\n', cursor);
            if (lineEnd < 0 || lineEnd > end) {
                lineEnd = end;
            }
            String line = text.substring(cursor, lineEnd).stripLeading();
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                if (level > 0 && level <= 6
                        && level < line.length() && line.charAt(level) == ' ') {
                    return cursor;
                }
            }
            cursor = lineEnd + 1;
        }
        return -1;
    }

    private static int skipLeadingWhitespace(String text, int start, int end) {
        int cursor = start;
        while (cursor < end && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int trimTrailingWhitespace(String text, int start, int end) {
        int cursor = end;
        while (cursor > start && Character.isWhitespace(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private static HeadingContext headingContext(String text, int position) {
        String section = null;
        List<String> path = new ArrayList<>();
        for (String line : text.substring(0, position).split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level == 0 || level > 6 || level >= trimmed.length()
                    || trimmed.charAt(level) != ' ') {
                continue;
            }
            String heading = trimmed.substring(level + 1).strip();
            if (heading.isBlank()) {
                continue;
            }
            while (path.size() >= level) {
                path.removeLast();
            }
            path.add(heading);
            section = heading;
        }
        return new HeadingContext(section, path.isEmpty() ? null : String.join(" > ", path));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record HeadingContext(String sectionTitle, String headingPath) {
    }
}
