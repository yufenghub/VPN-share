/* Copyright (C) 2021 Muntashir Al-Islam. LGPL-3.0-or-later.
 * Modified for secure entropy, bounded inputs, idempotent destruction and desktop tests. */
package io.github.muntashirakon.crypto.spake2;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.security.auth.Destroyable;

public final class Spake2Context implements Destroyable {
    static { System.loadLibrary("spake2"); }
    public static final int MAX_MSG_SIZE = 32, MAX_KEY_SIZE = 64;
    private long context;
    private final byte[] message = new byte[32];
    private boolean generated, processed, legacy;
    public Spake2Context(Spake2Role role, byte[] mine, byte[] theirs) {
        if (mine.length > 1024 || theirs.length > 1024) throw new IllegalArgumentException("Name size");
        context = allocate(role.ordinal(), mine, theirs);
        if (context == 0) throw new IllegalStateException("SPAKE2 allocation");
    }
    public synchronized byte[] getMyMsg() { return message.clone(); }
    public synchronized void setDisablePasswordScalarHack(boolean value) {
        checkLive();
        if (generated) throw new IllegalStateException("Message already generated");
        legacy = value;
    }
    public synchronized boolean isDisablePasswordScalarHack() { return legacy; }
    public synchronized byte[] generateMessage(byte[] password) {
        byte[] entropy = new byte[64];
        new SecureRandom().nextBytes(entropy);
        try { return generateMessage(password, entropy); }
        finally { Arrays.fill(entropy, (byte) 0); }
    }
    synchronized byte[] generateMessage(byte[] password, byte[] entropy) {
        checkLive();
        if (generated || entropy.length != 64 || password.length > 4096) throw new IllegalStateException("SPAKE2 state/input");
        byte[] result = generate(context, password, entropy, legacy);
        if (result == null || result.length != 32) throw new IllegalStateException("SPAKE2 message");
        generated = true;
        System.arraycopy(result, 0, message, 0, 32);
        return result;
    }
    public synchronized byte[] processMessage(byte[] peer) {
        checkLive();
        if (!generated || processed || peer.length != 32) throw new IllegalArgumentException("SPAKE2 state/input");
        byte[] result = process(context, peer);
        if (result == null || result.length != 64) throw new IllegalArgumentException("SPAKE2 peer point");
        processed = true;
        return result;
    }
    private void checkLive() { if (context == 0) throw new IllegalStateException("SPAKE2 destroyed"); }
    @Override public synchronized boolean isDestroyed() { return context == 0; }
    @Override public synchronized void destroy() {
        if (context != 0) { release(context); context = 0; Arrays.fill(message, (byte) 0); }
    }
    private static native long allocate(int role, byte[] mine, byte[] theirs);
    private static native byte[] generate(long ctx, byte[] password, byte[] entropy, boolean legacy);
    private static native byte[] process(long ctx, byte[] peer);
    private static native void release(long ctx);
}
