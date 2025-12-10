package com.exittrading.app.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class AppShutdownListener implements ApplicationListener<ContextClosedEvent> {
    private static final Logger log = LoggerFactory.getLogger(AppShutdownListener.class);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            log.warn("Application context is closing: {}", event.getApplicationContext().getId());
            // Emit a small thread dump for diagnostics
            StringBuilder sb = new StringBuilder("Thread dump on shutdown\n");
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                sb.append("[ ").append(t.getName()).append(" ] ").append(t.getState()).append('\n');
            }
            Logger errLog = LoggerFactory.getLogger("impact"); // reuse rolling file; not ideal but available
            errLog.info(sb.toString());
        } catch (Exception ignored) { }
    }
}

