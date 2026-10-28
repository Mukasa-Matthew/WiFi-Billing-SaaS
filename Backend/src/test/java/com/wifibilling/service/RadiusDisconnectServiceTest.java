package com.wifibilling.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RadiusDisconnectServiceTest {

    private final RadiusDisconnectService service = new RadiusDisconnectService();

    @Test
    void sendsValidRfc5176PacketOverUdp() throws Exception {
        // Bind an ephemeral UDP socket to receive the packet
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            int port = receiver.getLocalPort();
            receiver.setSoTimeout(3000);

            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<DatagramPacket> future = exec.submit(() -> {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.receive(packet);
                return packet;
            });

            boolean sent = service.sendDisconnect("127.0.0.1", port, "mySharedSecret", "VOUCHER123", "10.9.0.50");
            assertTrue(sent, "Packet should be sent successfully");

            DatagramPacket received = future.get(3, TimeUnit.SECONDS);
            assertNotNull(received);

            byte[] data = received.getData();
            // Code 40 = Disconnect-Request
            assertEquals(RadiusDisconnectService.CODE_DISCONNECT_REQUEST, data[0]);

            // Length (bytes 2 and 3)
            int length = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            assertEquals(received.getLength(), length);

            // Packet contains User-Name attribute (Type 1)
            // Attributes start at index 20 (after Code, Ident, Length, Authenticator)
            assertEquals(1, data[20]); // Type 1: User-Name
            int userAttrLen = data[21] & 0xFF;
            String usernameInPacket = new String(data, 22, userAttrLen - 2);
            assertEquals("VOUCHER123", usernameInPacket);

            exec.shutdown();
        }
    }

    /** Answers one Disconnect-Request the way a NAS would; `secret` signs the reply. */
    private static Future<?> respondOnce(DatagramSocket socket, byte code, String secret, boolean wrongIdentifier) {
        return Executors.newSingleThreadExecutor().submit(() -> {
            byte[] buf = new byte[1024];
            DatagramPacket req = new DatagramPacket(buf, buf.length);
            socket.receive(req);
            byte[] reply = new byte[20];
            reply[0] = code;
            reply[1] = wrongIdentifier ? (byte) (buf[1] + 1) : buf[1];
            reply[3] = 20;
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            md5.update(reply, 0, 4);
            md5.update(buf, 4, 16);            // request authenticator
            md5.update(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.arraycopy(md5.digest(), 0, reply, 4, 16);
            socket.send(new DatagramPacket(reply, reply.length, req.getAddress(), req.getPort()));
            return null;
        });
    }

    private RadiusDisconnectService.Result exchange(byte code, String replySecret, boolean wrongIdentifier) throws Exception {
        try (DatagramSocket nas = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            nas.setSoTimeout(3000);
            Future<?> f = respondOnce(nas, code, replySecret, wrongIdentifier);
            RadiusDisconnectService.Result r = service.disconnect("127.0.0.1", nas.getLocalPort(), "mySharedSecret", "VOUCHER123", null);
            f.get(3, TimeUnit.SECONDS);
            return r;
        }
    }

    @Test
    void signedAckFromTheNasIsReportedAsAck() throws Exception {
        assertEquals(RadiusDisconnectService.Result.ACK, exchange(RadiusDisconnectService.CODE_DISCONNECT_ACK, "mySharedSecret", false));
    }

    @Test
    void signedNakIsReportedAsNak() throws Exception {
        assertEquals(RadiusDisconnectService.Result.NAK, exchange(RadiusDisconnectService.CODE_DISCONNECT_NAK, "mySharedSecret", false));
    }

    @Test
    void forgedOrMismatchedRepliesAreNotTrusted() throws Exception {
        // Signed with the wrong secret, or answering a different request: never counted as an ACK.
        assertEquals(RadiusDisconnectService.Result.NO_REPLY, exchange(RadiusDisconnectService.CODE_DISCONNECT_ACK, "attackerSecret", false));
        assertEquals(RadiusDisconnectService.Result.NO_REPLY, exchange(RadiusDisconnectService.CODE_DISCONNECT_ACK, "mySharedSecret", true));
    }

    @Test
    void silentNasTimesOutAsNoReplyWithinTheBound() throws Exception {
        try (DatagramSocket nas = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            long t0 = System.nanoTime();
            RadiusDisconnectService.Result r = service.disconnect("127.0.0.1", nas.getLocalPort(), "mySharedSecret", "VOUCHER123", null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(RadiusDisconnectService.Result.NO_REPLY, r);
            assertTrue(ms < RadiusDisconnectService.REPLY_TIMEOUT_MS + 1500, "bounded wait, was " + ms + " ms");
        }
    }

    @Test
    void missingParametersAreAnErrorWithoutSending() {
        assertEquals(RadiusDisconnectService.Result.ERROR, service.disconnect(null, 3799, "s", "u", null));
        assertEquals(RadiusDisconnectService.Result.ERROR, service.disconnect("127.0.0.1", 3799, null, "u", null));
    }
}
