package com.astock.agent.web;

public final class UnsupportedModelCapabilityException extends IllegalArgumentException {

    public UnsupportedModelCapabilityException(String capability) {
        super("Unsupported model capability: " + capability);
    }
}
