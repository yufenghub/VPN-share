// SPDX-License-Identifier: Apache-2.0
package io.github.muntashirakon.adb;

import java.io.IOException;

/** Protocol stage and implementation metadata only; never contains a pairing code or key material. */
public final class PairingException extends IOException {
    public enum Stage {
        CONNECT, TLS_HANDSHAKE, TLS_EXPORT, SPAKE_INIT, SPAKE_SEND, SPAKE_RECEIVE,
        SPAKE_DERIVE, PEER_ENCRYPT, PEER_SEND, PEER_RECEIVE, PEER_DECRYPT
    }
    private final Stage stage;
    private final String provider;
    private final String socketType;

    public PairingException(Stage stage, String provider, String socketType, Throwable cause) {
        super("ADB pairing failed at " + stage.name(), cause);
        this.stage = stage;
        this.provider = provider;
        this.socketType = socketType;
    }
    public Stage getStage() { return stage; }
    public String getProvider() { return provider; }
    public String getSocketType() { return socketType; }
}
