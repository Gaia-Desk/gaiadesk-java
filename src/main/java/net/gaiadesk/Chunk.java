package net.gaiadesk;

import java.nio.charset.StandardCharsets;

/** A piece of a stream's output: which stream, and its bytes. */
public final class Chunk {
    /** Which stream a chunk is from. */
    public enum Stream {
        /** Standard output (and a job's log). */
        STDOUT,
        /** Standard error. */
        STDERR
    }

    private final Stream stream;
    private final byte[] data;

    /** A chunk of a stream. */
    public Chunk(Stream stream, byte[] data) {
        this.stream = stream;
        this.data = data.clone();
    }

    /** Which stream. */
    public Stream getStream() {
        return stream;
    }

    /** Its bytes (UTF-8 text: the API sends whole characters). */
    public byte[] getData() {
        return data.clone();
    }

    /** Its text. */
    public String getText() {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return stream + ": " + getText();
    }
}
