package com.kunukuntla.a11yinspector;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * The true time, the same on every phone: read from internet time servers (Google, Cloudflare,
 * pool.ntp.org - accurate to a few hundredths of a second), whatever the phone's own clock says.
 * Kept against the phone's running time (not its clock), so changing the phone's time doesn't
 * move it; checked again every 10 minutes.
 */
final class NetTime {

    private static final String[] SERVERS = {"time.google.com", "time.cloudflare.com", "pool.ntp.org"};
    private static final long RECHECK_MS = 10 * 60 * 1000;

    /** The true time (ms since 1970) at {@link #atElapsed}, and the phone's running time then. */
    private static volatile long trueAt, atElapsed;
    /** The phone's clock minus the true time when last checked (+ = phone ahead), ms. */
    private static volatile long phoneAhead;
    private static volatile String source = "";
    private static volatile boolean checking;

    private NetTime() {
    }

    static boolean synced() {
        return atElapsed != 0;
    }

    /** The true time now (ms since 1970); the phone's own clock until the first check works. */
    static long now() {
        if (!synced()) return System.currentTimeMillis();
        return trueAt + (SystemClock.elapsedRealtime() - atElapsed);
    }

    static long phoneAheadMs() {
        return phoneAhead;
    }

    static String source() {
        return source;
    }

    static long checkedAgoMs() {
        return synced() ? SystemClock.elapsedRealtime() - atElapsed : -1;
    }

    /** Checks the time in the background when it was never checked, or 10 minutes ago. */
    static void checkIfDue() {
        if (checking) return;
        if (synced() && SystemClock.elapsedRealtime() - atElapsed < RECHECK_MS) return;
        checking = true;
        new Thread(() -> {
            try {
                for (String host : SERVERS) {
                    if (ask(host)) break;
                }
            } finally {
                checking = false;
            }
        }, "net-time").start();
    }

    /** Three asks of one server; the one with the shortest round trip is kept (the most exact). */
    private static boolean ask(String host) {
        long bestRtt = Long.MAX_VALUE, bestTrue = 0, bestElapsed = 0, bestAhead = 0;
        for (int i = 0; i < 3; i++) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(2000);
                InetAddress addr = InetAddress.getByName(host);
                byte[] buf = new byte[48];
                buf[0] = 0x1B; // version 3, client
                long sendWall = System.currentTimeMillis();
                long sendEl = SystemClock.elapsedRealtime();
                writeTime(buf, 40, sendWall);
                s.send(new DatagramPacket(buf, buf.length, addr, 123));
                DatagramPacket in = new DatagramPacket(new byte[48], 48);
                s.receive(in);
                long recvEl = SystemClock.elapsedRealtime();
                byte[] r = in.getData();
                long serverRecv = readTime(r, 32);
                long serverSend = readTime(r, 40);
                long rtt = (recvEl - sendEl) - (serverSend - serverRecv);
                if (rtt < 0 || rtt > 3000) continue;
                // The server's send time plus half the way back = the true time at our receive.
                long trueAtRecv = serverSend + rtt / 2;
                if (rtt < bestRtt) {
                    bestRtt = rtt;
                    bestTrue = trueAtRecv;
                    bestElapsed = recvEl;
                    bestAhead = (sendWall + (recvEl - sendEl)) - trueAtRecv;
                }
            } catch (Exception ignored) {
            }
        }
        if (bestRtt == Long.MAX_VALUE) return false;
        trueAt = bestTrue;
        atElapsed = bestElapsed;
        phoneAhead = bestAhead;
        source = host + " (±" + Math.max(1, bestRtt / 2) + " ms)";
        return true;
    }

    // NTP time: seconds since 1900 (32 bits) and a fraction of a second (32 bits).
    private static final long OFFSET_1900 = 2208988800L;

    private static long readTime(byte[] b, int at) {
        long sec = ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
        long frac = ((b[at + 4] & 0xFFL) << 24) | ((b[at + 5] & 0xFFL) << 16) | ((b[at + 6] & 0xFFL) << 8) | (b[at + 7] & 0xFFL);
        return (sec - OFFSET_1900) * 1000 + (frac * 1000L >>> 32);
    }

    private static void writeTime(byte[] b, int at, long ms) {
        long sec = ms / 1000 + OFFSET_1900;
        long frac = ((ms % 1000) << 32) / 1000;
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (sec >>> (24 - 8 * i));
        for (int i = 0; i < 4; i++) b[at + 4 + i] = (byte) (frac >>> (24 - 8 * i));
    }
}
