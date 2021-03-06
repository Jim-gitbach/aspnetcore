// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.signalr;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.Completable;
import io.reactivex.subjects.CompletableSubject;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

class OkHttpWebSocketWrapper extends WebSocketWrapper {
    private WebSocket websocketClient;
    private String url;
    private Map<String, String> headers;
    private OkHttpClient client;
    private OnReceiveCallBack onReceive;
    private WebSocketOnClosedCallback onClose;
    private CompletableSubject startSubject = CompletableSubject.create();
    private CompletableSubject closeSubject = CompletableSubject.create();
    private final ReentrantLock stateLock = new ReentrantLock();

    private final Logger logger = LoggerFactory.getLogger(OkHttpWebSocketWrapper.class);

    public OkHttpWebSocketWrapper(String url, Map<String, String> headers, OkHttpClient client) {
        this.url = url;
        this.headers = headers;
        this.client = client;
    }

    @Override
    public Completable start() {
        Headers.Builder headerBuilder = new Headers.Builder();
        for (String key : headers.keySet()) {
            headerBuilder.add(key, headers.get(key));
        }

        Request request = new Request.Builder()
            .url(url)
            .headers(headerBuilder.build())
            .build();

        this.websocketClient = client.newWebSocket(request, new SignalRWebSocketListener());
        return startSubject;
    }

    @Override
    public Completable stop() {
        websocketClient.close(1000, "HubConnection stopped.");
        return closeSubject;
    }

    @Override
    public Completable send(ByteBuffer message) {
        ByteString bs = ByteString.of(message);
        websocketClient.send(bs);
        return Completable.complete();
    }

    @Override
    public void setOnReceive(OnReceiveCallBack onReceive) {
        this.onReceive = onReceive;
    }

    @Override
    public void setOnClose(WebSocketOnClosedCallback onClose) {
        this.onClose = onClose;
    }

    private class SignalRWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            stateLock.lock();
            try {
                startSubject.onComplete();
            } finally {
                stateLock.unlock();
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String message) {
            onReceive.invoke(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        }
        
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onReceive.invoke(bytes.asByteBuffer());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            boolean isOpen = false;
            stateLock.lock();
            try {
                isOpen = startSubject.hasComplete();
            } finally {
                stateLock.unlock();
            }

            logger.info("WebSocket closing with status code '{}' and reason '{}'.", code, reason);

            // Only call onClose if connection is open
            if (isOpen) {
                onClose.invoke(code, reason);
            }

            try {
                stateLock.lock();
                closeSubject.onComplete();
            }
            finally {
                stateLock.unlock();
            }
            checkStartFailure(null);

            // Send the close frame response if this was a server initiated close, otherwise noops
            webSocket.close(1000, "");
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            logger.error("WebSocket closed from an error.", t);

            boolean isOpen = false;
            try {
                stateLock.lock();
                if (!closeSubject.hasComplete()) {
                    closeSubject.onError(new RuntimeException(t));
                }

                isOpen = startSubject.hasComplete();
            }
            finally {
                stateLock.unlock();
            }
            // Only call onClose if connection is open
            if (isOpen) {
                onClose.invoke(null, t.getMessage());
            }
            checkStartFailure(t);
        }

        private void checkStartFailure(Throwable t) {
            stateLock.lock();
            try {
                // If the start task hasn't completed yet, then we need to complete it
                // exceptionally.
                if (!startSubject.hasComplete()) {
                    startSubject.onError(new RuntimeException("There was an error starting the WebSocket transport.", t));
                }
            } finally {
                stateLock.unlock();
            }
        }
    }
}
