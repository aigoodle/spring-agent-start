package io.github.aigoodle.web.support;

import io.github.aigoodle.common.exception.AgentException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Immutable, validated representation of a multipart document upload. */
public record UploadedDocument(String filename, byte[] content) {

    private static final String DEFAULT_FILENAME = "upload.bin";

    public static UploadedDocument from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AgentException("file_required", "An uploaded file is required", null);
        }
        try {
            return new UploadedDocument(filenameOf(file), file.getBytes());
        } catch (IOException exception) {
            throw new AgentException(
                    "upload_read_failed",
                    "Failed to read upload: " + exception.getMessage(),
                    exception);
        }
    }

    private static String filenameOf(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        return originalFilename == null || originalFilename.isBlank()
                ? DEFAULT_FILENAME
                : originalFilename;
    }
}
