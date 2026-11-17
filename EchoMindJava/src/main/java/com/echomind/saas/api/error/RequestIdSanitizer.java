package com.echomind.saas.api.error;

import java.util.regex.Pattern;

public final class RequestIdSanitizer {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String UNAVAILABLE = "unavailable";

    private RequestIdSanitizer() {
    }

    public static String sanitize(String requestId) {
        return requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()
                ? requestId
                : UNAVAILABLE;
    }
}
