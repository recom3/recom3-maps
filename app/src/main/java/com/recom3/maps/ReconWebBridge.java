package com.recom3.maps;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Keeps firmware Binder calls outside Maps so a lost session cannot wedge later tiles. */
final class ReconWebBridge {
    enum State { CHECKING, UNAVAILABLE, WAITING, READY }

    static final class Response {
        final int status;
        final Map<String, List<String>> headers;
        final byte[] body;

        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private final PhoneWebRequests requests;
    private boolean reportedUnavailable;

    ReconWebBridge(Context context) {
        requests = new PhoneWebRequests(new PhoneWebChannel.Factory(context.getApplicationContext()));
    }

    State probe() {
        try {
            PhoneWebRequests.Reply reply = requests.exchange(null,
                    Collections.<String, List<String>>emptyMap(), 5000);
            if (reply.state == PhoneWebRequests.UNAVAILABLE) {
                if (!reportedUnavailable) {
                    reportedUnavailable = true;
                    Log.i(TileTransport.TAG, "Recon SDK web service unavailable: " + reply.error);
                }
                return State.UNAVAILABLE;
            }
            return reply.state == PhoneWebRequests.READY ? State.READY : State.WAITING;
        } catch (IOException unavailable) {
            return State.WAITING;
        }
    }

    Response get(URL url, Map<String, List<String>> headers) throws IOException {
        PhoneWebRequests.Reply reply = requests.exchange(url.toExternalForm(), headers, 15000);
        if (reply.state != PhoneWebRequests.READY) throw new IOException("Waiting for phone web");
        if (reply.error != null || reply.status < 100) throw new IOException("Recon SDK web request failed");
        return new Response(reply.status, reply.headers, reply.body);
    }

    void close() { requests.close(); }
}
