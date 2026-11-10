package com.lab.sipim.web;

import com.lab.sipim.discovery.LanDiscovery;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DiscoveryController {
    private final LanDiscovery discovery;
    public DiscoveryController(LanDiscovery discovery) { this.discovery = discovery; }

    @GetMapping("/api/discovery")
    public Map<String, Object> descriptor(@RequestParam(defaultValue = "") String nonce) throws IOException {
        if (!nonce.matches("[0-9a-f]{32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discovery nonce");
        try { return discovery.descriptor(nonce); }
        catch (IllegalStateException disabled) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
