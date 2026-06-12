package com.gateflow.tracker.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求体，解决 Filter 消费 body 后下游无法读取的问题。
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request, String body) {
        super(request);
        this.body = body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        var bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return bais.read(); }
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) {}
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
