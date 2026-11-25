package com.recom3.maps;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One bound instance of the app-private, disposable SDK worker process. */
final class PhoneWebChannel implements PhoneWebRequests.Channel {
    static final int REQUEST = 1;
    static final int STOP = 2;

    static final class Factory implements PhoneWebRequests.Factory {
        private final Context context;
        private PhoneWebChannel previous;

        Factory(Context context) { this.context = context; }

        @Override public PhoneWebRequests.Channel create() throws IOException {
            // Do not bind another request to a process whose old SDK call is still running.
            if (previous != null && previous.closed && !previous.terminated()) {
                throw new IOException("Phone web helper restarting");
            }
            previous = new PhoneWebChannel(context);
            return previous;
        }
    }

    private final Context context;
    private final CountDownLatch dead = new CountDownLatch(1);
    private final Messenger replies;
    private PhoneWebRequests.Receiver receiver;
    private Messenger remote;
    private IBinder binder;
    private Message queued;
    private boolean bound;
    private volatile boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            boolean disconnected = false;
            synchronized (PhoneWebChannel.this) {
                if (closed) return;
                binder = service;
                remote = new Messenger(service);
                try {
                    service.linkToDeath(new IBinder.DeathRecipient() {
                        @Override public void binderDied() {
                            dead.countDown();
                            failed();
                        }
                    }, 0);
                    if (queued != null) remote.send(queued);
                    queued = null;
                } catch (RemoteException failure) {
                    disconnected = true;
                }
            }
            if (disconnected) failed();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            dead.countDown();
            failed();
        }
    };

    private PhoneWebChannel(Context context) {
        this.context = context;
        replies = new Messenger(new Replies(this));
    }

    private static final class Replies extends Handler {
        private final WeakReference<PhoneWebChannel> owner;

        Replies(PhoneWebChannel channel) {
            super(Looper.getMainLooper());
            owner = new WeakReference<PhoneWebChannel>(channel);
        }

        @Override public void handleMessage(Message message) {
            PhoneWebChannel channel = owner.get();
            if (channel != null) channel.receive(message);
        }
    }

    private void receive(Message message) {
        PhoneWebRequests.Receiver target;
        synchronized (this) {
            if (closed) return;
            target = receiver;
        }
        Bundle data = message.getData();
        target.reply(message.arg1, new PhoneWebRequests.Reply(data.getInt("state"),
                data.getInt("status"), readHeaders(data.getBundle("headers")),
                data.getByteArray("body"), data.getString("error")));
    }

    @Override public synchronized void open(PhoneWebRequests.Receiver receiver) throws IOException {
        this.receiver = receiver;
        bound = context.bindService(new Intent(context, PhoneWebService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) throw new IOException("Phone web helper unavailable");
    }

    @Override public synchronized void send(PhoneWebRequests.Request request) throws IOException {
        if (closed) throw new IOException("Phone web helper closed");
        Message message = Message.obtain(null, REQUEST);
        message.arg1 = request.id;
        message.replyTo = replies;
        Bundle data = new Bundle();
        data.putString("url", request.url);
        data.putBundle("headers", writeHeaders(request.headers));
        message.setData(data);
        if (remote == null) {
            queued = message;
            return;
        }
        try {
            remote.send(message);
        } catch (RemoteException disconnected) {
            throw new IOException("Phone web helper disconnected", disconnected);
        }
    }

    private void failed() {
        PhoneWebRequests.Receiver target;
        synchronized (this) {
            if (closed) return;
            target = receiver;
        }
        target.failed(new IOException("Phone web helper disconnected"));
    }

    private synchronized boolean terminated() {
        return binder == null || !binder.isBinderAlive() || dead.getCount() == 0;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            queued = null;
            if (remote != null) {
                try {
                    remote.send(Message.obtain(null, STOP));
                } catch (RemoteException ignored) {
                }
            }
            if (bound) {
                context.unbindService(connection);
                bound = false;
            }
        }
        // Messenger callbacks use the main thread; callers wait only on the tile worker.
        if (!terminated()) {
            try {
                dead.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static Bundle writeHeaders(Map<String, List<String>> headers) {
        Bundle bundle = new Bundle();
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    bundle.putStringArrayList(header.getKey(), new ArrayList<String>(header.getValue()));
                }
            }
        }
        return bundle;
    }

    static Map<String, List<String>> readHeaders(Bundle bundle) {
        if (bundle == null) return Collections.emptyMap();
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (String key : bundle.keySet()) headers.put(key, bundle.getStringArrayList(key));
        return headers;
    }
}
