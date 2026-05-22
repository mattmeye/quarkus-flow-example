package com.example.approval;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@WebSocket(path = "/approval-events")
@Singleton
public class ApprovalWebSocket implements ApprovalEventPublisher {

    @Inject
    OpenConnections connections;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        Log.infof("Approval WS opened: %s", connection.id());
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        Log.infof("Approval WS closed: %s", connection.id());
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable t) {
        Log.errorf(t, "Approval WS error %s", connection.id());
    }

    @Override
    public void publish(ApprovalEvent event) {
        connections.stream().forEach(c ->
                c.sendText(event).subscribe().with(
                        v -> { },
                        err -> Log.warnf("WS send failed for %s: %s", c.id(), err)));
    }
}
