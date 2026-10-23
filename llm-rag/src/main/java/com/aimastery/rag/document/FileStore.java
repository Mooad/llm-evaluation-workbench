package com.aimastery.rag.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Les fichiers importes, tels quels (state/files/, hors git).
 *
 * Sans eux, reindexer avec une autre strategie (ou un autre modele d'embedding) obligerait a
 * reimporter chaque document a la main. Nommes par documentId : l'empreinte du contenu.
 */
public class FileStore {

    private static final Pattern SAFE_ID = Pattern.compile("[a-f0-9]{16}");

    private final Path directory;

    public FileStore(Path directory) {
        this.directory = directory;
    }

    public void save(String documentId, byte[] content) {
        try {
            Files.createDirectories(directory);
            Files.write(path(documentId), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du fichier impossible dans " + directory.toAbsolutePath(), e);
        }
    }

    public Optional<byte[]> read(String documentId) {
        Path file = path(documentId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible : " + file, e);
        }
    }

    public void delete(String documentId) {
        try {
            Files.deleteIfExists(path(documentId));
        } catch (IOException e) {
            throw new UncheckedIOException("Suppression impossible : " + path(documentId), e);
        }
    }

    // Le documentId devient un nom de fichier : on refuse tout ce qui n'est pas une empreinte.
    private Path path(String documentId) {
        if (!SAFE_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("Identifiant de document invalide : " + documentId);
        }
        return directory.resolve(documentId + ".bin");
    }
}
