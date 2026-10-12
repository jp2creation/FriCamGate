package fr.degert.fricamgate;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@CapacitorPlugin(name = "FrigateLive")
public class FrigateLivePlugin extends Plugin {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build();
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    @PluginMethod
    public void connect(PluginCall call) {
        String id = call.getString("id");
        String url = call.getString("url");
        String authToken = call.getString("authToken");

        if (id == null || id.isEmpty() || url == null || url.isEmpty()) {
            call.reject("Missing live stream id or url");
            return;
        }

        closeSocket(id);

        Request.Builder request = new Request.Builder()
            .url(url)
            .header("Origin", "http://localhost");
        if (authToken != null && !authToken.isEmpty()) {
            request.header("Authorization", "Bearer " + authToken);
        }

        WebSocket socket = client.newWebSocket(request.build(), new FrigateLiveListener(id));
        sockets.put(id, socket);
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String id = call.getString("id");
        String message = call.getString("message");
        WebSocket socket = id == null ? null : sockets.get(id);

        if (socket == null || message == null) {
            call.reject("Live stream is not connected");
            return;
        }

        socket.send(message);
        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        String id = call.getString("id");
        if (id != null) {
            closeSocket(id);
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        for (String id : sockets.keySet()) {
            closeSocket(id);
        }
        super.handleOnDestroy();
    }

    private void closeSocket(String id) {
        WebSocket socket = sockets.remove(id);
        if (socket != null) {
            socket.close(1000, "closed");
        }
    }

    private void notifyLiveEvent(String eventName, String id) {
        JSObject payload = new JSObject();
        payload.put("id", id);
        notifyListeners(eventName, payload);
    }

    private class FrigateLiveListener extends WebSocketListener {
        private final String id;

        FrigateLiveListener(String id) {
            this.id = id;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            FrigateLivePlugin.this.notifyLiveEvent("open", id);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("data", text);
            notifyListeners("text", payload);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("data", bytes.base64());
            notifyListeners("binary", payload);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            sockets.remove(id);
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("message", throwable.getMessage());
            payload.put("status", response == null ? 0 : response.code());
            notifyListeners("error", payload);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            sockets.remove(id);
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("code", code);
            payload.put("reason", reason);
            notifyListeners("close", payload);
        }
    }
}
