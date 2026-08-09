/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import java.io.IOException;

public class RaopException extends IOException {
    public RaopException(String message) {
        super(message);
    }

    public RaopException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class AuthenticationException extends RaopException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static final class PairingException extends RaopException {
        public PairingException(String message) {
            super(message);
        }
    }

    public static final class UnsupportedReceiverException extends RaopException {
        private final RaopCompatibility compatibility;

        public UnsupportedReceiverException(String message, RaopCompatibility compatibility) {
            super(message);
            this.compatibility = compatibility;
        }

        public RaopCompatibility compatibility() {
            return compatibility;
        }
    }
}
