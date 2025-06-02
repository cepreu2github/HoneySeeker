package org.honeyseeker;

import java.util.List;

public interface Logger {
    void log(List<String> lines);
    void log(String line);
}
