/*
 * Copyright 2010 Shikhar Bhushan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file may incorporate work covered by the following copyright and
 * permission notice:
 *
 *     Licensed to the Apache Software Foundation (ASF) under one
 *     or more contributor license agreements.  See the NOTICE file
 *     distributed with this work for additional information
 *     regarding copyright ownership.  The ASF licenses this file
 *     to you under the Apache License, Version 2.0 (the
 *     "License"); you may not use this file except in compliance
 *     with the License.  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing,
 *      software distributed under the License is distributed on an
 *      "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *      KIND, either express or implied.  See the License for the
 *      specific language governing permissions and limitations
 *      under the License.
 */
package net.schmizz.sshj.transport;

import net.schmizz.concurrent.Event;
import net.schmizz.concurrent.FutureUtils;
import net.schmizz.sshj.AbstractService;
import net.schmizz.sshj.Config;
import net.schmizz.sshj.Service;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.common.Message;
import net.schmizz.sshj.common.SSHException;
import net.schmizz.sshj.common.SSHPacket;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** A thread-safe {@link Transport} implementation. */
public final class TransportImpl implements Transport {

    private static final class NullService extends AbstractService {
        NullService(Transport trans) {
            super("null-service", trans);
        }
    }

    static final class ConnInfo {
        final String host;
        final int port;
        final InputStream in;
        final OutputStream out;

        public ConnInfo(String host, int port, InputStream in, OutputStream out) {
            this.host = host;
            this.port = port;
            this.in = in;
            this.out = out;
        }
    }

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Service nullService = new NullService(this);

    private final Config config;

    private final KeyExchanger kexer;

    private final Reader reader;

    private final Heartbeater heartbeater;

    private final Encoder encoder;

    private final Decoder decoder;

    private final Event<TransportException> serviceAccept = new Event<TransportException>("service accept",
            TransportException.chainer);

    private final Event<TransportException> close = new Event<TransportException>("transport close",
            TransportException.chainer);

    /** Client version identification string */
    private final String clientID;

    private volatile int timeout = 30;

    private volatile boolean authed = false;

    /** Currently active service e.g. UserAuthService, ConnectionService */
    private volatile Service service = nullService;

    private ConnInfo connInfo;

    /** Server version identification string */
    private String serverID;

    /** Message identifier of last packet received */
    private Message msg;

    private final ReentrantLock writeLock = new ReentrantLock();

    public TransportImpl(Config config) {
        this.config = config;
        this.reader = new Reader(this);
        this.heartbeater = new Heartbeater(this);
        this.encoder = new Encoder(config.getRandomFactory().create(), writeLock);
        this.decoder = new Decoder(this);
        this.kexer = new KeyExchanger(this);
        clientID = "SSH-2.0-" + config.getVersion();
    }

    public void init(String remoteHost, int remotePort, InputStream in, OutputStream out) throws TransportException {
        connInfo = new ConnInfo(remoteHost, remotePort, in, out);

        try {

            log.info("Client identity string: {}", clientID);
            connInfo.out.write((clientID + "\r\n").getBytes());

            // Read server's ID
            final Buffer.PlainBuffer buf = new Buffer.PlainBuffer();
            while ((serverID = readIdentification(buf)).isEmpty()) {
                buf.putByte((byte) connInfo.in.read());
            }

            log.info("Server identity string: {}", serverID);

        } catch (IOException e) {
            throw new TransportException(e);
        }

        reader.start();
    }

    /**
     * Reads the identification string from the SSH server. This is the very first string that is sent upon connection
     * by the server. It takes the form of, e.g. "SSH-2.0-OpenSSH_ver".
     * <p/>
     * Several concerns are taken care of here, e.g. verifying protocol version, correct line endings as specified in
     * RFC and such.
     * <p/>
     * This is not efficient but is only done once.
     *
     * @param buffer
     *
     * @return
     *
     * @throws IOException
     */
    private String readIdentification(Buffer.PlainBuffer buffer) throws IOException {
        String ident;

        byte[] data = new byte[256];
        for (; ;) {
            int savedBufPos = buffer.rpos();
            int pos = 0;
            boolean needLF = false;
            for (; ;) {
                if (buffer.available() == 0) {
                    // Need more data, so undo reading and return null
                    buffer.rpos(savedBufPos);
                    return "";
                }
                byte b = buffer.readByte();
                if (b == '\r') {
                    needLF = true;
                    continue;
                }
                if (b == '\n')
                    break;
                if (needLF)
                    throw new TransportException("Incorrect identification: bad line ending");
                if (pos >= data.length)
                    throw new TransportException("Incorrect identification: line too long");
                data[pos++] = b;
            }
            ident = new String(data, 0, pos);
            if (ident.startsWith("SSH-"))
                break;
            if (buffer.rpos() > 16 * 1024)
                throw new TransportException("Incorrect identification: too many header lines");
        }

        if (!ident.startsWith("SSH-2.0-") && !ident.startsWith("SSH-1.99-"))
            throw new TransportException(DisconnectReason.PROTOCOL_VERSION_NOT_SUPPORTED,
                    "Server does not support SSHv2, identified as: " + ident);

        return ident;
    }

    public void addHostKeyVerifier(HostKeyVerifier hkv) {
        kexer.addHostKeyVerifier(hkv);
    }

    public void doKex() throws TransportException {
        kexer.startKex(true);
    }

    public boolean isKexDone() {
        return kexer.isKexDone();
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getHeartbeatInterval() {
        return heartbeater.getInterval();
    }

    public void setHeartbeatInterval(int interval) {
        heartbeater.setInterval(interval);
    }

    public String getRemoteHost() {
        return connInfo.host;
    }

    public int getRemotePort() {
        return connInfo.port;
    }

    public String getClientVersion() {
        return clientID.substring(8);
    }

    public Config getConfig() {
        return config;
    }

    public String getServerVersion() {
        return serverID == null ? serverID : serverID.substring(8);
    }

    public byte[] getSessionID() {
        return kexer.getSessionID();
    }

    public synchronized Service getService() {
        return service;
    }

    public synchronized void setService(Service service) {
        if (service == null)
            service = nullService;

        log.info("Setting active service to {}", service.getName());
        this.service = service;
    }

    public void reqService(Service service) throws TransportException {
        serviceAccept.lock();
        try {
            serviceAccept.clear();
            sendServiceRequest(service.getName());
            serviceAccept.await(timeout, TimeUnit.SECONDS);
            setService(service);
        } finally {
            serviceAccept.unlock();
        }
    }

    /**
     * Sends a service request for the specified service
     *
     * @param serviceName name of the service being requested
     *
     * @throws TransportException if there is an error while sending the request
     */
    private void sendServiceRequest(String serviceName) throws TransportException {
        log.debug("Sending SSH_MSG_SERVICE_REQUEST for {}", serviceName);
        write(new SSHPacket(Message.SERVICE_REQUEST).putString(serviceName));
    }

    public void setAuthenticated() {
        this.authed = true;
        encoder.setAuthenticated();
        decoder.setAuthenticated();
    }

    public boolean isAuthenticated() {
        return authed;
    }

    public long sendUnimplemented() throws TransportException {
        final long seq = decoder.getSequenceNumber();
        log.info("Sending SSH_MSG_UNIMPLEMENTED for packet #{}", seq);
        return write(new SSHPacket(Message.UNIMPLEMENTED).putInt(seq));
    }

    public void join() throws TransportException {
        close.await();
    }

    public boolean isRunning() {
        return reader.isAlive() && !close.isSet();
    }

    public void disconnect() {
        disconnect(DisconnectReason.BY_APPLICATION);
    }

    public void disconnect(DisconnectReason reason) {
        disconnect(reason, "");
    }

    public void disconnect(DisconnectReason reason, String message) {
        close.lock(); // CAS type operation on close
        try {
            try {
                service.notifyDisconnect();
            } catch (SSHException logged) {
                log.warn("{} did not handle disconnect cleanly: {}", service, logged);
            }
            if (!close.isSet()) {
                sendDisconnect(reason, message);
                finishOff();
                close.set();
            }
        } finally {
            close.unlock();
        }
    }

    public long write(SSHPacket payload) throws TransportException {
        writeLock.lock();
        try {

            if (kexer.isKexOngoing()) {
                // Only transport layer packets (1 to 49) allowed except SERVICE_REQUEST
                final Message m = Message.fromByte(payload.array()[payload.rpos()]);
                if (!m.in(1, 49) || m == Message.SERVICE_REQUEST) {
                    assert m != Message.KEXINIT;
                    kexer.waitForDone();
                }
            } else if (encoder.getSequenceNumber() == 0) // We get here every 2**32th packet
                kexer.startKex(true);

            final long seq = encoder.encode(payload);
            try {
                connInfo.out.write(payload.array(), payload.rpos(), payload.available());
                connInfo.out.flush();
            } catch (IOException ioe) {
                throw new TransportException(ioe);
            }

            return seq;

        } finally {
            writeLock.unlock();
        }
    }

    private void sendDisconnect(DisconnectReason reason, String message) {
        if (message == null)
            message = "";
        log.debug("Sending SSH_MSG_DISCONNECT: reason=[{}], msg=[{}]", reason, message);
        try {
            write(new SSHPacket(Message.DISCONNECT)
                    .putInt(reason.toInt())
                    .putString(message)
                    .putString(""));
        } catch (IOException logged) {
            log.warn("Error writing packet: {}", logged);
        }
    }

    /**
     * This is where all incoming packets are handled. If they pertain to the transport layer, they are handled here;
     * otherwise they are delegated to the active service instance if any via {@link Service#handle}.
     * <p/>
     * Even among the transport layer specific packets, key exchange packets are delegated to {@link
     * KeyExchanger#handle}.
     * <p/>
     * This method is called in the context of the {@link #reader} thread via {@link Decoder#received} when a full
     * packet has been decoded.
     *
     * @param msg the message identifer
     * @param buf buffer containg rest of the packet
     *
     * @throws SSHException if an error occurs during handling (unrecoverable)
     */
    public void handle(Message msg, SSHPacket buf) throws SSHException {
        this.msg = msg;

        log.trace("Received packet {}", msg);

        if (msg.geq(50)) // not a transport layer packet
            service.handle(msg, buf);

        else if (msg.in(20, 21) || msg.in(30, 49)) // kex packet
            kexer.handle(msg, buf);

        else
            switch (msg) {
                case DISCONNECT: {
                    gotDisconnect(buf);
                    break;
                }
                case IGNORE: {
                    log.info("Received SSH_MSG_IGNORE");
                    break;
                }
                case UNIMPLEMENTED: {
                    gotUnimplemented(buf);
                    break;
                }
                case DEBUG: {
                    gotDebug(buf);
                    break;
                }
                case SERVICE_ACCEPT: {
                    gotServiceAccept();
                    break;
                }
                default:
                    sendUnimplemented();
            }
    }

    private void gotDebug(SSHPacket buf) {
        boolean display = buf.readBoolean();
        String message = buf.readString();
        log.info("Received SSH_MSG_DEBUG (display={}) '{}'", display, message);
    }

    private void gotDisconnect(SSHPacket buf) throws TransportException {
        DisconnectReason code = DisconnectReason.fromInt(buf.readInt());
        String message = buf.readString();
        log.info("Received SSH_MSG_DISCONNECT (reason={}, msg={})", code, message);
        throw new TransportException(code, "Disconnected; server said: " + message);
    }

    private void gotServiceAccept() throws TransportException {
        serviceAccept.lock();
        try {
            if (!serviceAccept.hasWaiters())
                throw new TransportException(DisconnectReason.PROTOCOL_ERROR,
                        "Got a service accept notification when none was awaited");
            serviceAccept.set();
        } finally {
            serviceAccept.unlock();
        }
    }

    /**
     * Got an SSH_MSG_UNIMPLEMENTED, so lets see where we're at and act accordingly.
     *
     * @param buf
     *
     * @throws TransportException
     */
    private void gotUnimplemented(SSHPacket buf) throws SSHException {
        long seqNum = buf.readLong();
        log.info("Received SSH_MSG_UNIMPLEMENTED #{}", seqNum);
        if (kexer.isKexOngoing())
            throw new TransportException("Received SSH_MSG_UNIMPLEMENTED while exchanging keys");
        getService().notifyUnimplemented(seqNum);
    }

    private void finishOff() {
        reader.interrupt();
        heartbeater.interrupt();
        IOUtils.closeQuietly(connInfo.in);
        IOUtils.closeQuietly(connInfo.out);
    }

    void die(Exception ex) {
        close.lock();
        try {
            if (!close.isSet()) {

                log.error("Dying because - {}", ex.toString());

                final SSHException causeOfDeath = SSHException.chainer.chain(ex);

                FutureUtils.alertAll(causeOfDeath, close, serviceAccept);
                kexer.notifyError(causeOfDeath);
                getService().notifyError(causeOfDeath);
                setService(nullService);

                { // Perhaps can send disconnect packet to server
                    final boolean didNotReceiveDisconnect = msg != Message.DISCONNECT;
                    final boolean gotRequiredInfo = causeOfDeath.getDisconnectReason() != DisconnectReason.UNKNOWN;
                    if (didNotReceiveDisconnect && gotRequiredInfo)
                        sendDisconnect(causeOfDeath.getDisconnectReason(), causeOfDeath.getMessage());
                }

                finishOff();

                close.set();
            }
        } finally {
            close.unlock();
        }
    }

    String getClientID() {
        return clientID;
    }

    String getServerID() {
        return serverID;
    }

    Encoder getEncoder() {
        return encoder;
    }

    Decoder getDecoder() {
        return decoder;
    }

    ReentrantLock getWriteLock() {
        return writeLock;
    }

    ConnInfo getConnInfo() {
        return connInfo;
    }

}