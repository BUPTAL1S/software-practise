package com.lab.sipim.web;

import com.lab.sipim.esl.EslEventListener;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    @org.springframework.beans.factory.annotation.Autowired private com.lab.sipim.bootstrap.ApplicationReadiness readiness;

    private final EslEventListener eslListener;

    public HealthController(EslEventListener eslListener) {
        this.eslListener = eslListener;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", readiness == null || readiness.isReady() ? "ok" : "starting");
        if (eslListener != null) {
            Map<String, Object> esl = new LinkedHashMap<>();
            esl.put("enabled", eslListener.isEnabled());
            esl.put("connected", eslListener.isConnected());
            esl.put("target", eslListener.getTarget());
            resp.put("esl", esl);
        }
        return resp;
    }
}
