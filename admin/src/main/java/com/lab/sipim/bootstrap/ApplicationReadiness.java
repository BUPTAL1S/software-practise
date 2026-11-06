package com.lab.sipim.bootstrap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
/** Protects the HTTP startup window until migrations and identity backfill finish. */
@Component
public class ApplicationReadiness implements ApplicationListener<ApplicationReadyEvent> {
    private volatile boolean ready;
    public boolean isReady(){return ready;}
    @Override public void onApplicationEvent(ApplicationReadyEvent event){ready=true;}
}
