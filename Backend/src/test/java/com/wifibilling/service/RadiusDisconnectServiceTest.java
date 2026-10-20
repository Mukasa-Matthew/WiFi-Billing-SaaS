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
}
