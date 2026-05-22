package com.example.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/approval-events")
@Singleton
public class ApprovalWebSocket implements ApprovalEventPublisher {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private ObjectMapper mapper;

    @PostConstruct
    void init() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), session);
        Log.info("Approval WS opened: " + session.getId());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());
        Log.info("Approval WS closed: " + session.getId());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        Log.error("Approval WS error " + session.getId(), t);
        sessions.remove(session.getId());
    }

    @Override
    public void publish(ApprovalEvent event) {
        final String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            Log.error("Failed to serialize approval event", e);
            return;
        }
        sessions.values().forEach(s -> s.getAsyncRemote().sendText(payload, result -> {
            if (result.getException() != null) {
                Log.warn("WS send failed for " + s.getId() + ": " + result.getException());
            }
        }));
    }
}
