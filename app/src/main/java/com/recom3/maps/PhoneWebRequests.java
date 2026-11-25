package com.recom3.maps;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Serializes requests to a disposable process, never to an uninterruptible SDK thread. */
final class PhoneWebRequests {
    static final int UNAVAILABLE = 0, WAITING = 1, READY = 2;

    interface Receiver {
        void reply(int id, Reply reply);
        void failed(IOException failure);
    }

    interface Channel {
        void open(Receiver receiver) throws IOException;
        void send(Request request) throws IOException;
        void close();
    }

    interface Factory {
        Channel create() throws IOException;
    }

    static final class Request {
        final int id;
        final String url;
        final Map<String, List<String>> headers;

        Request(int id, String url, Map<String, List<String>> headers) {
            this.id = id;
            this.url = url;
            this.headers = headers;
        }
    }

    static final class Reply {
        final int state;
        final int status;
        final Map<String, List<String>> headers;
        final byte[] body;
        final String error;

        Reply(int state, int status, Map<String, List<String>> headers, byte[] body, String error) {
            this.state = state;
            this.status = status;
            this.headers = headers == null ? Collections.<String, List<String>>emptyMap() : headers;
            this.body = body;
            this.error = error;
        }
    }

    private static final class Pending {
        final int id;
        Reply reply;
        IOException failure;

        Pending(int id) { this.id = id; }
    }

    private final Object lock = new Object();
    private final Factory factory;
    private Channel channel;
    private Pending pending;
    private int sequence;
    private boolean closed;
    private boolean retiring;

    PhoneWebRequests(Factory factory) { this.factory = factory; }

    static int state(boolean connected, boolean webAvailable) {
        return connected && webAvailable ? READY : WAITING;
    }

    Reply exchange(String url, Map<String, List<String>> headers, long timeoutMillis) throws IOException {
        Channel discarded = null;
        try {
            synchronized (lock) {
                if (closed) throw new IOException("Phone web closed");
                if (retiring) throw new IOException("Phone web helper restarting");
                if (pending != null) throw new IOException("Phone web request already running");
                final Pending call = new Pending(++sequence);
                pending = call;
                try {
                    if (channel == null) {
                        final Channel opened = factory.create();
                        channel = opened;
                        opened.open(new Receiver() {
                            @Override public void reply(int id, Reply reply) {
                                synchronized (lock) {
                                    if (channel != opened || pending == null || pending.id != id) return;
                                    pending.reply = reply;
                                    lock.notifyAll();
                                }
                            }

                            @Override public void failed(IOException failure) {
                                synchronized (lock) {
                                    if (channel != opened || pending == null) return;
                                    pending.failure = failure;
                                    lock.notifyAll();
                                }
                            }
                        });
                    }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                    channel.send(new Request(call.id, url, headers));
                    while (call.reply == null && call.failure == null && !closed) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new IOException("Phone web request timed out");
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    }
                    if (closed) throw new IOException("Phone web closed");
                    if (call.failure != null) throw call.failure;
                    return call.reply;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    discarded = channel;
                    channel = null;
                    retiring = discarded != null;
                    throw new IOException("Phone web request interrupted", interrupted);
                } catch (IOException failure) {
                    discarded = channel;
                    channel = null;
                    retiring = discarded != null;
                    throw failure;
                } finally {
                    pending = null;
                }
            }
        } finally {
            if (discarded != null) {
                try {
                    discarded.close();
                } finally {
                    synchronized (lock) { retiring = false; }
                }
            }
        }
    }

    void close() {
        Channel discarded;
        synchronized (lock) {
            closed = true;
            discarded = channel;
            channel = null;
            lock.notifyAll();
        }
        if (discarded != null) discarded.close();
    }
}
