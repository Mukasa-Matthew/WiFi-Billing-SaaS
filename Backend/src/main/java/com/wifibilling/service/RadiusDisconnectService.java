package com.wifibilling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Sends RFC 5176 Disconnect-Request (PoD) packets over UDP (default port 3799).
 * Packet format:
 *   1 byte: Code = 40 (Disconnect-Request)
 *   1 byte: Identifier (0-255)
 *   2 bytes: Length (total packet size)
 *   16 bytes: Request Authenticator (random 16 bytes for Disconnect-Request)
 *   Variable: RADIUS Attributes (Type, Length, Value)
 *
 * For RFC 5176, the Authenticator transmitted is:
 *   MD5(Code + Identifier + Length + 16 zero octets + Attributes + SharedSecret)
 */
@Slf4j
@Service
public class RadiusDisconnectService {

    public static final byte CODE_DISCONNECT_REQUEST = 40; // 0x28
    public static final int DEFAULT_COA_PORT = 3799;

    // RADIUS Standard Attribute Types (RFC 2865 / RFC 2866)
    public static final int ATTR_USER_NAME = 1;
    public static final int ATTR_FRAMED_IP_ADDRESS = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Sends a Disconnect-Request to the target NAS.
     *
     * @param nasIp destination IP (router's WireGuard IP)
     * @param port destination port (typically 3799)
     * @param sharedSecret radius secret configured between FreeRADIUS and the router
     * @param username username / voucher code to terminate
     * @param framedIpAddress optional client IP (can be null)
     * @return true if datagram sent successfully, false otherwise
     */
    public boolean sendDisconnect(String nasIp, int port, String sharedSecret, String username, String framedIpAddress) {
        // Fire-and-forget, as before: bulk callers (expiry) must not wait on replies.
        return send(nasIp, port, sharedSecret, username, framedIpAddress, false) != Result.ERROR;
    }

    /** Outcome of one Disconnect-Request. NO_REPLY is common when this host has no route back from the tunnel. */
    public enum Result { ACK, NAK, NO_REPLY, ERROR }

    public static final byte CODE_DISCONNECT_ACK = 41;
    public static final byte CODE_DISCONNECT_NAK = 42;
    static final int REPLY_TIMEOUT_MS = 3000;

    /**
     * Sends a Disconnect-Request and waits briefly for the NAS's answer. A
     * reply only counts when its identifier matches and its Response
     * Authenticator verifies against the shared secret (RFC 5176 §3.5).
     */
    public Result disconnect(String nasIp, int port, String sharedSecret, String username, String framedIpAddress) {
        return send(nasIp, port, sharedSecret, username, framedIpAddress, true);
    }

    private Result send(String nasIp, int port, String sharedSecret, String username, String framedIpAddress, boolean awaitReply) {
        if (nasIp == null || nasIp.isBlank() || sharedSecret == null || username == null) {
            log.warn("Missing parameters for Disconnect-Request: nasIp={}, user={}", nasIp, username);
            return Result.ERROR;
        }
        try {
            byte identifier = (byte) RANDOM.nextInt(256);
            byte[] attributes = buildAttributes(username, framedIpAddress);
            int totalLength = 1 + 1 + 2 + 16 + attributes.length;
            if (totalLength > 4096) {
                log.error("Disconnect packet size exceeds limit: {}", totalLength);
                return Result.ERROR;
            }
            byte[] packet = new byte[totalLength];
            packet[0] = CODE_DISCONNECT_REQUEST;
            packet[1] = identifier;
            packet[2] = (byte) ((totalLength >> 8) & 0xFF);
            packet[3] = (byte) (totalLength & 0xFF);
            // 16 octets of zeroes for the authenticator calculation (RFC 5176 Section 3.3)
            Arrays.fill(packet, 4, 20, (byte) 0);
            System.arraycopy(attributes, 0, packet, 20, attributes.length);
            // Authenticator = MD5(Code + Identifier + Length + 16 zero octets + Attributes + Secret)
            byte[] secretBytes = sharedSecret.getBytes(StandardCharsets.UTF_8);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(packet);
            md5.update(secretBytes);
            byte[] authenticator = md5.digest();
            System.arraycopy(authenticator, 0, packet, 4, 16);

            InetAddress targetAddr = InetAddress.getByName(nasIp);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(REPLY_TIMEOUT_MS);
                socket.send(new DatagramPacket(packet, packet.length, targetAddr, port));
                log.info("Sent Disconnect-Request (RFC 5176) to {}:{} for user '{}' (ident: {})", nasIp, port, username, identifier & 0xFF);
                if (!awaitReply) return Result.NO_REPLY;
                byte[] buf = new byte[4096];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (java.net.SocketTimeoutException e) {
                    return Result.NO_REPLY;
                }
                return verifyReply(buf, reply.getLength(), identifier, authenticator, secretBytes);
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("Failed to send Disconnect-Request to {}:{}: {}", nasIp, port, e.getMessage());
            return Result.ERROR;
        }
    }

    /** ACK/NAK only for a well-formed reply to this request; anything else is treated as no reply. */
    static Result verifyReply(byte[] buf, int len, byte identifier, byte[] requestAuthenticator, byte[] secret) {
        if (len < 20 || buf[1] != identifier) return Result.NO_REPLY;
        int declared = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        if (declared < 20 || declared > len) return Result.NO_REPLY;
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(buf, 0, 4);
            md5.update(requestAuthenticator);
            md5.update(buf, 20, declared - 20);
            md5.update(secret);
            byte[] expected = md5.digest();
            if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(buf, 4, 20))) return Result.NO_REPLY;
        } catch (NoSuchAlgorithmException e) {
            return Result.NO_REPLY;
        }
        return buf[0] == CODE_DISCONNECT_ACK ? Result.ACK : buf[0] == CODE_DISCONNECT_NAK ? Result.NAK : Result.NO_REPLY;
    }

    private byte[] buildAttributes(String username, String framedIpAddress) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // 1. User-Name (Type 1)
        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        baos.write(ATTR_USER_NAME);
        baos.write(2 + userBytes.length); // Length includes Type (1) + Length (1)
        baos.write(userBytes);

        // 2. Framed-IP-Address (Type 8, length 6) if present and valid IPv4
        if (framedIpAddress != null && !framedIpAddress.isBlank()) {
            try {
                InetAddress ip = InetAddress.getByName(framedIpAddress.trim());
                byte[] ipBytes = ip.getAddress();
                if (ipBytes.length == 4) { // Only IPv4 for Framed-IP-Address
                    baos.write(ATTR_FRAMED_IP_ADDRESS);
                    baos.write(6);
                    baos.write(ipBytes);
                }
            } catch (Exception ignored) {
            }
        }

        return baos.toByteArray();
    }
}
