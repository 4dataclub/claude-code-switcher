package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

/** SSE-Broadcast fuer Live-UI-Updates (Status-Aenderungen, Auto-Failover). */
@Service
public class SseService {

    private final CopyOnWriteArraySet<SseEmitter> clients = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter register() {
        SseEmitter em = new SseEmitter(60_000L * 60); // 1h
        em.onCompletion(() -> clients.remove(em));
        em.onTimeout(() -> clients.remove(em));
        em.onError((e) -> clients.remove(em));
        clients.add(em);
        return em;
    }

    public void broadcast(String event, Object data) {
        try {
            String json = mapper.writeValueAsString(data);
            for (SseEmitter em : clients) {
                try {
                    em.send(SseEmitter.event().name(event).data(json));
                } catch (IOException e) {
                    clients.remove(em);
                }
            }
        } catch (Exception ignored) {}
    }
}
