package com.astock.agent.marketdata.provider;

import java.net.URI;

public final class ProviderException extends RuntimeException {

    private final ProviderId provider;
    private final URI uri;
    private final int statusCode;

    public ProviderException(ProviderId provider, URI uri, int statusCode, String message) {
        super(message);
        this.provider = provider;
        this.uri = uri;
        this.statusCode = statusCode;
    }

    public ProviderException(ProviderId provider, URI uri, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.uri = uri;
        this.statusCode = -1;
    }

    public ProviderId provider() {
        return provider;
    }

    public URI uri() {
        return uri;
    }

    public int statusCode() {
        return statusCode;
    }
}
