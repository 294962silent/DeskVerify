package com.echomind.saas.api.error;

public final class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("resource not found", null, false, false);
    }
}
