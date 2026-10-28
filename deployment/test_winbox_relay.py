import asyncio
import base64
import importlib.util
import pathlib
import sys
import time
import unittest

spec = importlib.util.spec_from_file_location("winbox_relay", pathlib.Path(__file__).with_name("winbox-relay.py"))
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class RelayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.sources = []
        self.targets = []
        for ip, label in [("127.0.0.2", b"router-one:"), ("127.0.0.3", b"router-two:")]:
            async def echo(reader, writer, name=label):
                self.sources.append(writer.get_extra_info("peername")[0])
                try:
                    while data := await reader.read(4096):
                        writer.write(name + data)
                        await writer.drain()
                finally:
                    writer.close()
                    await writer.wait_closed()
            self.targets.append(await asyncio.start_server(echo, ip, 8291))
        self.relay = module.Relay("127.0.0.0/8", "127.0.0.1", "127.0.0.1", "127.0.0.1")
        self.key = base64.b64encode(bytes(range(32))).decode()
        self.key2 = base64.b64encode(bytes(reversed(range(32)))).decode()
        async def peers():
            return {"127.0.0.2/32": (self.key, int(time.time())), "127.0.0.3/32": (self.key2, int(time.time()))}
        async def dns(host):
            return True
        self.relay.peers = peers
        self.relay.dns_matches = dns

    def payload(self, rows=None):
        return {"publicHost": "localhost", "hubIp": "127.0.0.1", "subnet": "127.0.0.0/8", "leaseSeconds": 15, "routes": rows if rows is not None else [
            {"routerId": 1, "tenantId": 7, "port": 30000, "tunnelIp": "127.0.0.2", "peerKey": self.key, "revision": 1}]}

    async def asyncTearDown(self):
        await self.relay.clear()
        for target in self.targets:
            target.close()
            await target.wait_closed()

    async def test_real_tcp_mapping_and_revocation_close_existing_and_new_connections(self):
        checks = await self.relay.reconcile(self.payload())
        self.assertEqual(checks[0]["result"], "READY")
        reader, writer = await asyncio.open_connection("127.0.0.1", 30000)
        writer.write(b"WinBox-probe")
        await writer.drain()
        self.assertEqual(await asyncio.wait_for(reader.read(100), 2), b"router-one:WinBox-probe")
        self.assertEqual(self.sources[-1], "127.0.0.1")
        await asyncio.wait_for(self.relay.reconcile(self.payload([])), 3)
        self.assertEqual(await asyncio.wait_for(reader.read(100), 2), b"")
        writer.close()
        await writer.wait_closed()
        with self.assertRaises(OSError):
            await asyncio.open_connection("127.0.0.1", 30000)

    async def test_two_tenant_ports_reach_only_their_assigned_router(self):
        payload = self.payload()
        payload["routes"].append({"routerId": 2, "tenantId": 8, "port": 30001, "tunnelIp": "127.0.0.3", "peerKey": self.key2, "revision": 1})
        await self.relay.reconcile(payload)
        for port, expected in [(30000, b"router-one:"), (30001, b"router-two:")]:
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            writer.write(b"identity")
            await writer.drain()
            self.assertEqual(await asyncio.wait_for(reader.read(100), 2), expected + b"identity")
            writer.close()
            await writer.wait_closed()

    async def test_wrong_peer_and_dns_do_not_open_ports(self):
        async def missing():
            return {}
        self.relay.peers = missing
        self.assertEqual((await self.relay.reconcile(self.payload()))[0]["result"], "OFFLINE")
        async def wrong_dns(host):
            return False
        self.relay.dns_matches = wrong_dns
        self.assertEqual((await self.relay.reconcile(self.payload()))[0]["result"], "DNS_FAILED")
        self.assertFalse(self.relay.servers)

    async def test_mismatched_and_stale_peer_identity_are_rejected(self):
        async def mismatch():
            return {"127.0.0.2/32": (self.key2, int(time.time()))}
        self.relay.peers = mismatch
        self.assertEqual((await self.relay.reconcile(self.payload()))[0]["result"], "OFFLINE")
        async def stale():
            return {"127.0.0.2/32": (self.key, int(time.time()) - 181)}
        self.relay.peers = stale
        self.assertEqual((await self.relay.reconcile(self.payload()))[0]["result"], "OFFLINE")
        self.assertFalse(self.relay.servers)

    async def test_expired_control_lease_removes_listeners(self):
        await self.relay.reconcile(self.payload())
        self.relay.deadline = time.monotonic() - 1
        expiry = asyncio.create_task(self.relay.expire())
        await asyncio.sleep(0.6)
        expiry.cancel()
        await asyncio.gather(expiry, return_exceptions=True)
        self.assertFalse(self.relay.servers)

    async def test_port_collision_is_reported_not_reassigned(self):
        occupied = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 30000)
        try:
            self.assertEqual((await self.relay.reconcile(self.payload()))[0]["result"], "FORWARDING_FAILED")
            self.assertFalse(self.relay.routes)
        finally:
            occupied.close()
            await occupied.wait_closed()

    async def test_validation_rejects_open_relay_targets_and_duplicate_allocations(self):
        data = self.payload()
        data["hubIp"] = "127.0.0.4"
        with self.assertRaises(ValueError):
            self.relay.validate(data)
        for field, value in [("tunnelIp", "8.8.8.8"), ("port", 22), ("tunnelIp", "127.0.0.1"), ("revision", -1)]:
            data = self.payload()
            data["routes"][0][field] = value
            with self.assertRaises(ValueError):
                self.relay.validate(data)
        data = self.payload()
        data["routes"].append(dict(data["routes"][0]))
        with self.assertRaises(ValueError):
            self.relay.validate(data)

    async def test_rate_limits_new_connection_attempts(self):
        for _ in range(10):
            self.assertTrue(self.relay.admit("192.0.2.10"))
        self.assertFalse(self.relay.admit("192.0.2.10"))


if __name__ == "__main__":
    unittest.main()
