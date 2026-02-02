package net.swofty.storage;

import net.swofty.data.DataFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class FileDataStorage implements DataStorage {
    private final Path baseDir;
    private final DataFormat format;
    private final String extension;

    public FileDataStorage(Path baseDir, DataFormat format) {
        this(baseDir, format, ".dat");
    }

    public FileDataStorage(Path baseDir, DataFormat format, String extension) {
        this.baseDir = baseDir;
        this.format = format;
        this.extension = extension;
    }

    private Path resolvePath(String type, String id) {
        return baseDir.resolve(type).resolve(id + extension);
    }

    @Override
    public byte[] load(String type, String id) {
        Path path = resolvePath(type, id);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void save(String type, String id, byte[] data) {
        Path path = resolvePath(type, id);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> listIds(String type) {
        Path dir = baseDir.resolve(type);
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(extension))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - extension.length());
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String type, String id) {
        Path path = resolvePath(type, id);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String type, String id) {
        return Files.exists(resolvePath(type, id));
    }

    public DataFormat getFormat() {
        return format;
    }
}
