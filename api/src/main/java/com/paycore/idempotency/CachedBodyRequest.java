package com.paycore.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads the body once so the filter can hash it and the controller can still deserialize it. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("request body exceeds " + maxBytes + " bytes");
        }
        this.body = bytes;
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // synchronous only
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
