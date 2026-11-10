package com.lab.sipim.discovery;

import com.lab.sipim.bootstrap.ApplicationReadiness;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A separate, credential-free LAN discovery channel. No SIP or media operations. */
@Component
public class LanDiscovery implements AutoCloseable {
    public static final int PORT = 39771;
    public static final String GROUP = "239.255.71.80";
    private static final Logger log = LoggerFactory.getLogger(LanDiscovery.class);
    private final ApplicationReadiness readiness;
    private final boolean enabled;
    private final int discoveryPort, sipPort;
    private final Path identityFile;
    private volatile MulticastSocket socket;
    private volatile int httpPort;
    private String instanceId;

    public LanDiscovery(ApplicationReadiness readiness,
            @Value("${sipconnect.discovery.enabled:true}") boolean enabled,
            @Value("${sipconnect.discovery.port:39771}") int discoveryPort,
            @Value("${sipconnect.discovery.sip-port:5060}") int sipPort,
            @Value("${sipconnect.discovery.identity-file:./data/.discovery-id}") String identityFile) {
        this.readiness = readiness; this.enabled = enabled;
        this.discoveryPort = discoveryPort; this.sipPort = sipPort;
        this.identityFile = Path.of(identityFile).toAbsolutePath();
    }

    public synchronized String instanceId() throws IOException {
        if (instanceId == null) {
            Files.createDirectories(identityFile.getParent());
            try {
                Files.writeString(identityFile, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException existing) { /* Preserve server identity across restarts/IP changes. */ }
            String saved = Files.readString(identityFile).trim();
            if (!saved.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IOException("Invalid discovery identity file");
            instanceId = saved;
        }
        return instanceId;
    }

    public Map<String, Object> descriptor(String nonce) throws IOException {
        if (!enabled || httpPort <= 0 || !readiness.isReady())
            throw new IllegalStateException("Discovery not ready");
        return Map.of("service", "sip-connect", "version", 1, "instanceId", instanceId(),
                "httpPort", httpPort, "sipPort", sipPort, "nonce", nonce);
    }

    @EventListener
    public void onWebServerReady(ServletWebServerInitializedEvent event) {
        httpPort = event.getWebServer().getPort();
        if (!enabled) return;
        try { start(); }
        catch (IOException failure) {
            // Failure of this optional channel must not prevent normal/manual login.
            log.warn("LAN discovery unavailable on UDP {}: {}", discoveryPort, failure.toString());
        }
    }

    synchronized void start() throws IOException {
        if (socket != null) return;
        instanceId();
        MulticastSocket listener = new MulticastSocket(null);
        try {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(discoveryPort));
            listener.setBroadcast(true);
            listener.setTimeToLive(1);
            InetSocketAddress group = new InetSocketAddress(GROUP, discoveryPort);
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (nic.isUp() && !nic.isLoopback() && !nic.isVirtual() && nic.supportsMulticast())
                        listener.joinGroup(group, nic);
                } catch (IOException unavailableInterface) { /* Broadcast and other interfaces remain usable. */ }
            }
            socket = listener;
            Thread thread = new Thread(() -> receive(listener), "sip-connect-discovery");
            thread.setDaemon(true); thread.start();
            log.info("LAN discovery listening on UDP {}", listener.getLocalPort());
        } catch (IOException failure) { listener.close(); throw failure; }
    }

    int listeningPort() { return socket == null ? 0 : socket.getLocalPort(); }

    private void receive(MulticastSocket listener) {
        long window = System.nanoTime(); int replies = 0;
        while (!listener.isClosed()) {
            try {
                byte[] bytes = new byte[256];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                listener.receive(packet);
                if (!(packet.getAddress() instanceof Inet4Address) || !readiness.isReady()) continue;
                String query = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
                if (!query.matches("DISCOVER SIPCONNECT/1 [0-9a-f]{32}")) continue;
                long now = System.nanoTime();
                if (now - window >= 1_000_000_000L) { replies = 0; window = now; }
                if (++replies > 40) continue;
                String nonce = query.substring("DISCOVER SIPCONNECT/1 ".length());
                byte[] reply = ("SIPCONNECT/1 " + nonce + " " + instanceId() + " " + httpPort + " " + sipPort)
                        .getBytes(StandardCharsets.US_ASCII);
                listener.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
            } catch (IOException failure) {
                if (!listener.isClosed()) log.debug("Discovery datagram failed: {}", failure.toString());
            }
        }
    }

    @Override @PreDestroy public synchronized void close() {
        if (socket != null) { socket.close(); socket = null; }
    }
}
