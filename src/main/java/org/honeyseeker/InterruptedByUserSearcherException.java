package org.honeyseeker;

public class InterruptedByUserSearcherException extends SearcherException {
    public InterruptedByUserSearcherException(String message, String currentFile, String currentEntry,
                                              Throwable cause) {
        super(message, currentFile, currentEntry, cause);
    }
}
