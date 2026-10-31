package com.lab.sipim.esl;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EslFrameReaderTest {
    private static byte[] frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ("Content-Type: text/event-plain\r\nContent-Length: " + bytes.length + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test void unicodeAndConsecutiveFramesKeepTheirExactBoundaries() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(frame("Caller-Name: 张同学\nConference-Name: room-101\n"));
        bytes.write(frame("Action: del-member\n"));
        var reader = new EslFrameReader(new ByteArrayInputStream(bytes.toByteArray()));
        assertThat(reader.read().body()).contains("张同学");
        assertThat(reader.read().body()).isEqualTo("Action: del-member\n");
        assertThat(reader.read()).isNull();
    }

    @Test void fragmentedBodyIsAssembledWithoutConsumingTheNextFrame() throws Exception {
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(frame("中文事件"))) {
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
        assertThat(new EslFrameReader(fragmented).read().body()).isEqualTo("中文事件");
    }

    @Test void missingBodyLengthIsValidForCommandReplies() throws Exception {
        var input = new ByteArrayInputStream("Content-Type: command/reply\nReply-Text: +OK accepted\n\n".getBytes(StandardCharsets.UTF_8));
        var frame = new EslFrameReader(input).read();
        assertThat(frame.header("Reply-Text")).isEqualTo("+OK accepted");
        assertThat(frame.body()).isEmpty();
    }

    @Test void invalidAndExcessiveLengthsAreRejectedBeforeAllocation() {
        for (String length : new String[]{"-1", "1048577", "many", "9999999999999999"}) {
            var input = new ByteArrayInputStream(("Content-Length: " + length + "\n\n").getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> new EslFrameReader(input).read()).isInstanceOf(IOException.class);
        }
    }

    @Test void incompleteHeadersAndBodiesAreNotAcceptedAsEvents() {
        for (String partial : new String[]{"Content-T", "Content-Length: 6\n\nabc"}) {
            assertThatThrownBy(() -> new EslFrameReader(new ByteArrayInputStream(partial.getBytes(StandardCharsets.UTF_8))).read())
                    .isInstanceOf(EOFException.class);
        }
    }

    @Test void malformedAndOversizedHeadersAreRejected() {
        for (String header : new String[]{"not a header\n\n", "X: " + "x".repeat(17000) + "\n\n"}) {
            assertThatThrownBy(() -> new EslFrameReader(new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8))).read())
                    .isInstanceOf(IOException.class);
        }
    }

    @Test void idleTimeoutIsDistinguishedFromPartialFrameTimeout() {
        InputStream idle = new InputStream() { @Override public int read() throws IOException { throw new SocketTimeoutException(); } };
        assertThatThrownBy(() -> new EslFrameReader(idle).read()).isInstanceOf(EslFrameReader.IdleTimeout.class);
        InputStream partial = new SequenceInputStream(new ByteArrayInputStream("Content-Length:".getBytes(StandardCharsets.UTF_8)), idle);
        assertThatThrownBy(() -> new EslFrameReader(partial).read()).isInstanceOf(IOException.class)
                .isNotInstanceOf(EslFrameReader.IdleTimeout.class);
    }
}
