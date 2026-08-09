package app.shengdu.audio.dacp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the temporary DACP endpoint advertised while one RAOP stream is active.
 * The endpoint disappears with the stream and uses per-session credentials.
 */
public final class DacpSession implements Closeable {
    private static final String SERVICE_TYPE = "_dacp._tcp.";
    private static final String SERVICE_NAME_PREFIX = "iTunes_Ctrl_";
    private static final long REGISTRATION_TIMEOUT_SECONDS = 3;

    private final NsdManager nsdManager;
    private final DacpControlServer controlServer;
    private final String dacpId;
    private final String activeRemote;
    private final Object registrationLock = new Object();
    private final CountDownLatch registrationResult = new CountDownLatch(1);

    private NsdManager.RegistrationListener registrationListener;
    private boolean registrationSucceeded;
    private boolean registered;
    private boolean closed;

    public static DacpSession start(
            Context context,
            DacpControlServer.Listener listener) throws IOException {
        return start(context, null, listener);
    }

    public static DacpSession start(
            Context context,
            InetAddress expectedPeerAddress,
            DacpControlServer.Listener listener) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");

        NsdManager nsdManager = context.getApplicationContext().getSystemService(NsdManager.class);
        if (nsdManager == null) {
            throw new IOException("Android NSD service is unavailable");
        }

        SecureRandom random = new SecureRandom();
        String dacpId = randomHexId(random);
        String activeRemote = randomActiveRemote(random);
        DacpControlServer server = new DacpControlServer(
                null,
                0,
                expectedPeerAddress,
                activeRemote,
                listener);
        int port = server.start();

        DacpSession session = new DacpSession(nsdManager, server, dacpId, activeRemote);
        try {
            session.register(port);
            session.awaitRegistration();
            return session;
        } catch (IOException exception) {
            session.close();
            throw exception;
        } catch (RuntimeException exception) {
            session.close();
            throw new IOException("Could not advertise the DACP control service", exception);
        }
    }

    private DacpSession(
            NsdManager nsdManager,
            DacpControlServer controlServer,
            String dacpId,
            String activeRemote) {
        this.nsdManager = nsdManager;
        this.controlServer = controlServer;
        this.dacpId = dacpId;
        this.activeRemote = activeRemote;
    }

    public String dacpId() {
        return dacpId;
    }

    public String activeRemote() {
        return activeRemote;
    }

    private void register(int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(SERVICE_NAME_PREFIX + dacpId);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);
        serviceInfo.setAttribute("txtvers", "1");
        serviceInfo.setAttribute("Ver", "131075");
        serviceInfo.setAttribute("DbId", dacpId);
        serviceInfo.setAttribute("OSsi", "0x1F5");

        NsdManager.RegistrationListener listener = new RegistrationCallbacks();
        synchronized (registrationLock) {
            registrationListener = listener;
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    private void awaitRegistration() throws IOException {
        final boolean completed;
        try {
            completed = registrationResult.await(REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while advertising the DACP control service", exception);
        }
        synchronized (registrationLock) {
            if (!completed || !registrationSucceeded) {
                throw new IOException(completed
                        ? "Android rejected the DACP control service"
                        : "Timed out advertising the DACP control service");
            }
        }
    }

    @Override
    public void close() {
        NsdManager.RegistrationListener listenerToUnregister = null;
        synchronized (registrationLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (registered) {
                listenerToUnregister = registrationListener;
                registered = false;
            }
        }
        controlServer.close();
        if (listenerToUnregister != null) {
            unregister(listenerToUnregister);
        }
    }

    private void unregister(NsdManager.RegistrationListener listener) {
        try {
            nsdManager.unregisterService(listener);
        } catch (IllegalArgumentException ignored) {
            clearRegistration(listener);
        }
    }

    private void clearRegistration(NsdManager.RegistrationListener listener) {
        synchronized (registrationLock) {
            if (registrationListener == listener) {
                registrationListener = null;
                registered = false;
            }
        }
    }

    private final class RegistrationCallbacks implements NsdManager.RegistrationListener {
        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            synchronized (registrationLock) {
                registrationSucceeded = false;
            }
            clearRegistration(this);
            controlServer.close();
            registrationResult.countDown();
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            boolean unregisterNow;
            boolean expectedName = (SERVICE_NAME_PREFIX + dacpId).equals(serviceInfo.getServiceName());
            synchronized (registrationLock) {
                if (registrationListener != this) {
                    return;
                }
                registered = true;
                registrationSucceeded = expectedName;
                unregisterNow = closed || !expectedName;
                if (unregisterNow) {
                    registered = false;
                }
            }
            registrationResult.countDown();
            if (unregisterNow) {
                unregister(this);
                if (!expectedName) {
                    controlServer.close();
                }
            }
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            clearRegistration(this);
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            clearRegistration(this);
        }
    }

    static String randomHexId(SecureRandom random) {
        byte[] value = new byte[8];
        random.nextBytes(value);
        StringBuilder result = new StringBuilder(16);
        for (byte current : value) {
            result.append(String.format(Locale.US, "%02X", current & 0xff));
        }
        return result.toString();
    }

    static String randomActiveRemote(SecureRandom random) {
        int value;
        do {
            value = random.nextInt();
        } while (value == 0);
        return Integer.toUnsignedString(value);
    }
}
