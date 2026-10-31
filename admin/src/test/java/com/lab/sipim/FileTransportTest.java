package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "SIPIM_DB=./target/test-file-transport.db", "freeswitch.esl.enabled=false", "sipim.files.upload-dir=target/transport-uploads"})
class FileTransportTest {
  @LocalServerPort int port;
  @Autowired com.lab.sipim.security.LoginTokens tokens;
  @Autowired com.lab.sipim.repo.UserRepository users;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

  @Test void twoMiBMultipartPassesRealServletAndProfileLabelDoesNotPublishIt() throws Exception {
    String token = tokens.issue(users.findByUsername("1002").orElseThrow().getId());
    String prefix = "--transport-test\r\nContent-Disposition: form-data; name=\"to\"\r\n\r\nprofile\r\n"
        + "--transport-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.bin\"\r\n"
        + "Content-Type: application/octet-stream\r\n\r\n";
    byte[] payload = new byte[2 * 1024 * 1024];
    java.util.Arrays.fill(payload, (byte) 42);
    var client = HttpClient.newHttpClient();
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/files"))
        .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=transport-test")
        .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
            HttpRequest.BodyPublishers.ofByteArray(payload), HttpRequest.BodyPublishers.ofString("\r\n--transport-test--\r\n"))).build();
    var uploaded = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(uploaded.statusCode()).isEqualTo(200);
    var data = json.readTree(uploaded.body());
    assertThat(data.get("size").asLong()).isEqualTo(payload.length);
    URI uri = URI.create("http://127.0.0.1:" + port + data.get("url").asText());
    assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    var downloaded = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertThat(downloaded.body()).isEqualTo(payload);
  }
}
