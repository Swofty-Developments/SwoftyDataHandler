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
    public synchronized void save(String type, String id, byte[] data) {
        write(type, id, data, readVersion(type, id) + 1);
    }

    // Only a single JVM's writers are serialised here, so the compare and the write are atomic
    // for this process and no further. Two processes over one directory still race.
    @Override
    public synchronized SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
        long current = readVersion(type, id);
        if (expectedVersion != VersionedData.ANY_VERSION && current != expectedVersion) {
            return SaveResult.conflict(type, id, current);
        }
        long version = current + 1;
        write(type, id, data, version);
        return SaveResult.saved(type, id, version);
    }

    @Override
    public synchronized VersionedData loadVersioned(String type, String id) {
        return new VersionedData(load(type, id), readVersion(type, id));
    }

    private void write(String type, String id, byte[] data, long version) {
        Path path = resolvePath(type, id);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
            Files.writeString(versionPath(type, id), Long.toString(version));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The version lives beside the document rather than inside it, because the document bytes are
    // the consumer's on-disk format and must stay byte-for-byte what the codecs wrote.
    private Path versionPath(String type, String id) {
        return baseDir.resolve(type).resolve(id + extension + ".version");
    }

    private long readVersion(String type, String id) {
        Path path = versionPath(type, id);
        if (!Files.exists(path)) return VersionedData.UNVERSIONED;
        try {
            return Long.parseLong(Files.readString(path).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NumberFormatException corrupt) {
            return VersionedData.UNVERSIONED;
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
            Files.deleteIfExists(versionPath(type, id));
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
