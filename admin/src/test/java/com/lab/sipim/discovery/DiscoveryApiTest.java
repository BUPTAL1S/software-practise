package com.lab.sipim.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"sipconnect.discovery.enabled=true", "sipconnect.discovery.port=0", "freeswitch.esl.enabled=false"})
@AutoConfigureMockMvc
class DiscoveryApiTest {
    private static final String RUN = "./target/discovery-" + UUID.randomUUID();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + RUN + ".db");
        r.add("sipconnect.discovery.identity-file", () -> RUN + "/identity");
    }
    @Autowired LanDiscovery discovery;
    @Autowired MockMvc mvc;
    @LocalServerPort int httpPort;

    @Test void unauthenticatedDescriptorExposesOnlyConnectionMetadataAndNonce() throws Exception {
        String nonce = "a".repeat(32);
        mvc.perform(get("/api/discovery").param("nonce", nonce)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.service").value("sip-connect"))
                .andExpect(jsonPath("$.instanceId").value(discovery.instanceId()))
                .andExpect(jsonPath("$.httpPort").value(httpPort))
                .andExpect(jsonPath("$.nonce").value(nonce))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/discovery").param("nonce", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/workbench/users/page")).andExpect(status().isUnauthorized());
    }

    @Test void udpReplyCarriesActualHttpPortAndRestartKeepsIdentity(@TempDir Path temp) throws Exception {
        try (DatagramSocket client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            client.setSoTimeout(1500);
            String nonce = "b".repeat(32);
            byte[] query = ("DISCOVER SIPCONNECT/1 " + nonce).getBytes(StandardCharsets.US_ASCII);
            client.send(new DatagramPacket(query, query.length, InetAddress.getLoopbackAddress(), discovery.listeningPort()));
            DatagramPacket reply = new DatagramPacket(new byte[256], 256); client.receive(reply);
            assertThat(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII))
                    .isEqualTo("SIPCONNECT/1 " + nonce + " " + discovery.instanceId() + " " + httpPort + " 5060");
        }
        String file = temp.resolve("server-id").toString();
        var first = new LanDiscovery(null, true, 0, 5060, file);
        var restarted = new LanDiscovery(null, true, 0, 5060, file);
        assertThat(restarted.instanceId()).isEqualTo(first.instanceId());
    }
}
