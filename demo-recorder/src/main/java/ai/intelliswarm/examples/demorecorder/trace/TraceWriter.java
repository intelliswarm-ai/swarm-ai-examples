package ai.intelliswarm.examples.demorecorder.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Atomic JSON writer: serialise to {@code *.tmp} then rename, so concurrent readers
 * (regression CLI, website dev server) never see a partial write.
 */
public class TraceWriter {

    private final ObjectMapper mapper;

    public TraceWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(Map<String, Object> trace, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), trace);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
