package com.recom3.maps;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import com.reconinstruments.os.HUDOS;
import com.reconinstruments.os.connectivity.HUDConnectivityManager;
import com.reconinstruments.os.connectivity.IHUDConnectivity;
import com.reconinstruments.os.connectivity.http.HUDHttpRequest;
import com.reconinstruments.os.connectivity.http.HUDHttpResponse;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Runs only in :phoneweb; stopping it leaves Maps and the firmware services running. */
public final class PhoneWebService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private HUDConnectivityManager manager;
    private HandlerThread sdkEvents;
    private FutureTask<HUDConnectivityManager> initialization;
    private Messenger endpoint;

    @Override public void onCreate() {
        super.onCreate();
        // The SDK constructs a Handler on its caller's Looper. Keep that Looper free to
        // receive connection events even while the separate request worker is in Binder.
        sdkEvents = new HandlerThread("PhoneWebSdkEvents");
        sdkEvents.start();
        initialization = new FutureTask<HUDConnectivityManager>(new Callable<HUDConnectivityManager>() {
            @Override public HUDConnectivityManager call() {
                String[] libraries = getPackageManager().getSystemSharedLibraryNames();
                if (libraries == null || !Arrays.asList(libraries).contains("com.reconinstruments.os")) return null;
                Object service = HUDOS.getHUDService(HUDOS.HUD_CONNECTIVITY_SERVICE);
                if (!(service instanceof HUDConnectivityManager)) return null;
                System.loadLibrary("reconinstruments_jni");
                return (HUDConnectivityManager) service;
            }
        });
        new Handler(sdkEvents.getLooper()).post(initialization);
        endpoint = new Messenger(new Requests(this));
    }

    private static final class Requests extends Handler {
        private final WeakReference<PhoneWebService> owner;

        Requests(PhoneWebService service) { owner = new WeakReference<PhoneWebService>(service); }

        @Override public void handleMessage(Message message) {
            PhoneWebService service = owner.get();
            if (service != null) service.dispatch(message);
        }
    }

    private void dispatch(Message message) {
        if (message.what == PhoneWebChannel.STOP) {
            Process.killProcess(Process.myPid());
        } else if (message.what == PhoneWebChannel.REQUEST && message.replyTo != null) {
            final int id = message.arg1;
            final Messenger target = message.replyTo;
            final Bundle request = message.getData();
            worker.execute(new Runnable() {
                @Override public void run() { respond(target, id, request); }
            });
        }
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private int probe() throws Exception {
        if (manager == null) {
            try {
                // The client bounds this whole probe and can stop this process if init blocks.
                manager = initialization.get();
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof LinkageError) throw (LinkageError) failed.getCause();
                throw failed;
            }
        }
        if (manager == null) return PhoneWebRequests.UNAVAILABLE;
        // SPP/MFi accessory connectivity does not mean its HTTP channel has reopened.
        return PhoneWebRequests.state(manager.getConnectionState() == IHUDConnectivity.ConnectionState.CONNECTED,
                manager.hasWebConnection());
    }

    private void respond(Messenger target, int id, Bundle request) {
        Bundle reply = new Bundle();
        reply.putInt("state", PhoneWebRequests.WAITING);
        try {
            int state = probe();
            reply.putInt("state", state);
            if (state == PhoneWebRequests.UNAVAILABLE) reply.putString("error", "connectivity manager not exposed");
            String address = request.getString("url");
            if (state == PhoneWebRequests.READY && address != null) {
                URL url = new URL(address);
                if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
                    throw new IOException("Invalid tile endpoint");
                }
                HUDHttpRequest query = new HUDHttpRequest(HUDHttpRequest.RequestMethod.GET, url,
                        PhoneWebChannel.readHeaders(request.getBundle("headers")));
                query.setTimeout(10000);
                HUDHttpResponse response = manager.sendWebRequest(query);
                if (response == null) throw new IOException("Empty SDK response");
                byte[] body = response.getBody();
                if (body != null && body.length > TileTransport.MAX_TILE_BYTES) throw new IOException("Tile is too large");
                reply.putInt("status", response.getResponseCode());
                reply.putBundle("headers", PhoneWebChannel.writeHeaders(response.getHeaders()));
                reply.putByteArray("body", body);
            }
        } catch (LinkageError unavailable) {
            reply.putInt("state", PhoneWebRequests.UNAVAILABLE);
            reply.putString("error", unavailable.getClass().getSimpleName());
        } catch (Exception failure) {
            reply.putString("error", failure.getClass().getSimpleName());
        }
        Message message = Message.obtain(null, PhoneWebChannel.REQUEST);
        message.arg1 = id;
        message.setData(reply);
        try {
            target.send(message);
        } catch (RemoteException disconnected) {
            Process.killProcess(Process.myPid());
        }
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        sdkEvents.quit();
        super.onDestroy();
        // Thread interruption cannot cancel an SDK Binder call; this private process can.
        Process.killProcess(Process.myPid());
    }
}
