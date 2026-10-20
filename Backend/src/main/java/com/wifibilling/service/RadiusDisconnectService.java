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
        if (nasIp == null || nasIp.isBlank() || sharedSecret == null || username == null) {
            log.warn("Missing parameters for Disconnect-Request: nasIp={}, user={}", nasIp, username);
            return false;
        }

        try {
            byte identifier = (byte) RANDOM.nextInt(256);
            byte[] attributes = buildAttributes(username, framedIpAddress);
            int totalLength = 1 + 1 + 2 + 16 + attributes.length;

            if (totalLength > 4096) {
                log.error("Disconnect packet size exceeds limit: {}", totalLength);
                return false;
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

            // Insert calculated authenticator into packet bytes [4..19]
            System.arraycopy(authenticator, 0, packet, 4, 16);

            InetAddress targetAddr = InetAddress.getByName(nasIp);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                DatagramPacket datagram = new DatagramPacket(packet, packet.length, targetAddr, port);
                socket.send(datagram);
                log.info("Sent Disconnect-Request (RFC 5176) to {}:{} for user '{}' (ident: {})", nasIp, port, username, identifier & 0xFF);
                return true;
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("Failed to send Disconnect-Request to {}:{}: {}", nasIp, port, e.getMessage());
            return false;
        }
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
