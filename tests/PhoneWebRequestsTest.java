package com.recom3.maps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Host regressions: no phone, SDK, Android runtime or network access required. */
public final class PhoneWebRequestsTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static final Map<String, List<String>> HEADERS = Collections.singletonMap(
            "If-None-Match", Collections.singletonList("tile-version"));
    private static final PhoneWebRequests.Reply READY = reply(PhoneWebRequests.READY);

    private static PhoneWebRequests.Reply reply(int state) {
        return new PhoneWebRequests.Reply(state, 0, null, null, null);
    }

    private static final class Channel implements PhoneWebRequests.Channel {
        PhoneWebRequests.Receiver receiver;
        PhoneWebRequests.Request last;
        PhoneWebRequests.Reply response = READY;
        boolean hang;
        boolean fail;
        boolean closed;
        final CountDownLatch sent = new CountDownLatch(1);
        final CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch allowClose;

        @Override public void open(PhoneWebRequests.Receiver receiver) { this.receiver = receiver; }
        @Override public void send(PhoneWebRequests.Request request) {
            last = request;
            sent.countDown();
            if (fail) receiver.failed(new IOException("helper died"));
            else if (!hang) receiver.reply(request.id, response);
        }
        @Override public void close() {
            closed = true;
            closing.countDown();
            if (allowClose != null) {
                try {
                    if (!allowClose.await(2, TimeUnit.SECONDS)) throw new AssertionError("Close barrier expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private static final class Factory implements PhoneWebRequests.Factory {
        final List<Channel> channels = new ArrayList<Channel>();
        int next;

        Channel add() {
            Channel channel = new Channel();
            channels.add(channel);
            return channel;
        }
        @Override public PhoneWebRequests.Channel create() {
            if (next > 0) check(channels.get(next - 1).closed, "Retire old channel before replacement");
            return channels.get(next++);
        }
    }

    private static void check(boolean condition, String reason) {
        checks.incrementAndGet();
        if (!condition) throw new AssertionError(reason);
    }

    private static void fails(PhoneWebRequests requests, String reason) throws Exception {
        try {
            requests.exchange("https://example.invalid/tile.png", HEADERS, 25);
            throw new AssertionError("Expected " + reason);
        } catch (IOException expected) {
            check(expected.getMessage().contains(reason), "Expected failure: " + reason);
        }
    }

    private static void readiness() {
        check(PhoneWebRequests.state(false, false) == PhoneWebRequests.WAITING, "Disconnected waits");
        check(PhoneWebRequests.state(true, false) == PhoneWebRequests.WAITING, "Accessory-only reconnect waits");
        check(PhoneWebRequests.state(false, true) == PhoneWebRequests.WAITING, "Stale web state waits");
        check(PhoneWebRequests.state(true, true) == PhoneWebRequests.READY, "Web plus accessory is ready");
    }

    private static void repliesAndRecovery() throws Exception {
        Factory factory = new Factory();
        Channel first = factory.add();
        first.response = reply(PhoneWebRequests.WAITING);
        PhoneWebRequests requests = new PhoneWebRequests(factory);
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.WAITING, "Probe reports waiting");
        first.response = READY;
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.READY, "Later web recovery is visible");
        byte[] body = { 1, 2, 3, 4 };
        first.response = new PhoneWebRequests.Reply(PhoneWebRequests.READY, 200, HEADERS, body, null);
        PhoneWebRequests.Reply result = requests.exchange("https://example.invalid/tile.png", HEADERS, 100);
        check(result.status == 200 && result.body == body && result.headers.equals(HEADERS), "Preserve binary HTTP response");
        check(first.last.url.equals("https://example.invalid/tile.png") && first.last.headers.equals(HEADERS), "Preserve request validators");
        check(factory.next == 1, "Reuse healthy helper");
        requests.close();
        check(first.closed, "Close healthy helper");
    }

    private static void stuckRequest() throws Exception {
        Factory factory = new Factory();
        Channel stuck = factory.add();
        stuck.hang = true; // Simulates a Binder call that ignores Java interruption.
        Channel recovered = factory.add();
        PhoneWebRequests requests = new PhoneWebRequests(factory);
        fails(requests, "timed out");
        check(stuck.closed, "Timeout terminates the stuck helper");
        int staleId = stuck.last.id;
        stuck.receiver.reply(staleId, reply(PhoneWebRequests.UNAVAILABLE));
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.READY, "Fresh helper recovers after timeout");
        check(recovered.last.id != staleId, "Replacement has a fresh request identity");
        stuck.receiver.failed(new IOException("old process died"));
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.READY, "Ignore retired helper death");
        requests.close();
    }

    private static void helperDeath() throws Exception {
        Factory factory = new Factory();
        Channel dead = factory.add();
        dead.fail = true;
        factory.add();
        PhoneWebRequests requests = new PhoneWebRequests(factory);
        fails(requests, "helper died");
        check(dead.closed, "Discard disconnected helper");
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.READY, "Recover after helper death");
        requests.close();
    }

    private static void interruptedAndClosed() throws Exception {
        Factory factory = new Factory();
        Channel channel = factory.add();
        channel.hang = true;
        PhoneWebRequests requests = new PhoneWebRequests(factory);
        Thread.currentThread().interrupt();
        try {
            fails(requests, "interrupted");
            check(Thread.currentThread().isInterrupted(), "Preserve interruption");
            check(channel.closed, "Interruption retires helper");
        } finally {
            Thread.interrupted();
            requests.close();
        }
        fails(requests, "closed");
        check(factory.next == 1, "Closed client does not create another helper");
    }

    private static void closePending() throws Exception {
        Factory factory = new Factory();
        final Channel channel = factory.add();
        channel.hang = true;
        final PhoneWebRequests requests = new PhoneWebRequests(factory);
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    requests.exchange(null, HEADERS, 10000);
                    outcome.set(new AssertionError("Closed request returned a reply"));
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("closed")) outcome.set(expected);
                }
            }
        });
        waiter.start();
        check(channel.sent.await(2, TimeUnit.SECONDS), "Pending request started");
        requests.close();
        waiter.join(2000);
        check(!waiter.isAlive() && outcome.get() == null, "Closing wakes pending caller");
        check(channel.closed, "Closing pending request terminates its helper");
    }

    private static void wrongReply() throws Exception {
        Factory factory = new Factory();
        final Channel channel = factory.add();
        channel.hang = true;
        final PhoneWebRequests requests = new PhoneWebRequests(factory);
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    check(requests.exchange(null, HEADERS, 2000).state == PhoneWebRequests.READY,
                            "Receive matching response");
                } catch (Throwable failure) { outcome.set(failure); }
            }
        });
        waiter.start();
        check(channel.sent.await(2, TimeUnit.SECONDS), "Request awaits reply");
        channel.receiver.reply(channel.last.id - 1, reply(PhoneWebRequests.UNAVAILABLE));
        channel.receiver.reply(channel.last.id, READY);
        waiter.join(2500);
        check(!waiter.isAlive() && outcome.get() == null, "Ignore reply for another request");
        requests.close();
    }

    private static void retiringHelper() throws Exception {
        Factory factory = new Factory();
        final Channel old = factory.add();
        old.hang = true;
        old.allowClose = new CountDownLatch(1);
        factory.add();
        final PhoneWebRequests requests = new PhoneWebRequests(factory);
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try { fails(requests, "timed out"); }
                catch (Throwable failure) { outcome.set(failure); }
            }
        });
        waiter.start();
        check(old.closing.await(2, TimeUnit.SECONDS), "Timed out helper starts shutdown");
        try {
            fails(requests, "restarting");
            check(factory.next == 1, "No replacement before helper termination");
        } finally {
            old.allowClose.countDown();
        }
        waiter.join(2500);
        check(!waiter.isAlive() && outcome.get() == null, "Timed out request finishes shutdown");
        check(requests.exchange(null, HEADERS, 100).state == PhoneWebRequests.READY,
                "Retry succeeds after helper termination");
        requests.close();
    }

    public static void main(String[] args) throws Exception {
        readiness();
        repliesAndRecovery();
        stuckRequest();
        helperDeath();
        interruptedAndClosed();
        closePending();
        wrongReply();
        retiringHelper();
        System.out.println("Phone web regressions passed: " + checks.get() + " checks");
    }
}
