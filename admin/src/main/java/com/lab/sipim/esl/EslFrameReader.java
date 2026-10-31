package com.lab.sipim.esl;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads ESL Content-Length as UTF-8 bytes and never resumes a partially read frame. */
final class EslFrameReader {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private final InputStream input;

    EslFrameReader(InputStream input) { this.input = input; }

    record Frame(Map<String, String> headers, String body) {
        String header(String name) { return headers.get(name); }
    }

    /** An idle socket can remain connected; a timeout inside a frame requires reconnecting. */
    static final class IdleTimeout extends SocketTimeoutException {}

    Frame read() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = 0;
        boolean consumed = false;
        try {
            while (true) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                while (true) {
                    int value = input.read();
                    if (value < 0) {
                        if (!consumed) return null;
                        throw new EOFException("Incomplete ESL headers");
                    }
                    consumed = true;
                    if (++headerBytes > MAX_HEADER_BYTES) throw new IOException("ESL headers too large");
                    if (value == '\n') break;
                    if (value != '\r') line.write(value);
                }
                String text = line.toString(StandardCharsets.UTF_8);
                if (text.isEmpty()) break;
                int colon = text.indexOf(':');
                if (colon <= 0) throw new IOException("Malformed ESL header");
                headers.put(text.substring(0, colon).trim(), text.substring(colon + 1).trim());
            }
            int length = 0;
            String declared = headers.get("Content-Length");
            if (declared != null) {
                try { length = Integer.parseInt(declared); }
                catch (NumberFormatException error) { throw new IOException("Invalid ESL Content-Length", error); }
                if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("ESL body length out of range");
            }
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Incomplete ESL body");
            return new Frame(Map.copyOf(headers), new String(body, StandardCharsets.UTF_8));
        } catch (SocketTimeoutException timeout) {
            if (!consumed) throw new IdleTimeout();
            throw new IOException("Timeout inside ESL frame", timeout);
        }
    }
}
