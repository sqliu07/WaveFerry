package app.shengdu.audio.discovery;

import java.util.Objects;

public final class DiscoveryError {
    private final String operation;
    private final String serviceType;
    private final String serviceName;
    private final int errorCode;
    private final String message;

    public DiscoveryError(
            String operation,
            String serviceType,
            String serviceName,
            int errorCode,
            String message
    ) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.serviceType = serviceType == null ? "" : serviceType;
        this.serviceName = serviceName == null ? "" : serviceName;
        this.errorCode = errorCode;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getOperation() {
        return operation;
    }

    public String getServiceType() {
        return serviceType;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }
}
