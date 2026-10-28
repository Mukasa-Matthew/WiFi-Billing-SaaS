#!/usr/bin/env python3
import asyncio
import base64
import collections
import ipaddress
import json
import os
import re
import socket
import ssl
import subprocess
import time
import urllib.request
from dataclasses import dataclass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Control redirects are not permitted")


@dataclass(frozen=True)
class Route:
    router_id: int
    tenant_id: int
    port: int
    tunnel_ip: str
    peer_key: str
    revision: int


class Relay:
    def __init__(self, network, hub_ip, bind_ip, public_ip, interface="wg0"):
        self.network = ipaddress.IPv4Network(network)
        self.hub_ip = str(ipaddress.IPv4Address(hub_ip))
        self.bind_ip = str(ipaddress.IPv4Address(bind_ip))
        self.public_ip = str(ipaddress.IPv4Address(public_ip))
        if ipaddress.IPv4Address(self.hub_ip) not in self.network:
            raise ValueError("Hub address must belong to the WireGuard subnet")
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", interface):
            raise ValueError("Invalid WireGuard interface")
        self.interface = interface
        self.routes = {}
        self.servers = {}
        self.connections = collections.defaultdict(set)
        self.per_ip = collections.Counter()
        self.attempts = {}
        self.deadline = 0

    def validate(self, payload):
        if payload.get("hubIp") != self.hub_ip or ipaddress.IPv4Network(payload["subnet"], strict=False) != self.network:
            raise ValueError("Router and relay WireGuard configurations do not match")
        if payload.get("leaseSeconds") != 15 or len(payload["routes"]) > 10000:
            raise ValueError("Invalid lease or route count")
        host = payload["publicHost"]
        if not isinstance(host, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}", host):
            raise ValueError("Invalid public hostname")
        result = {}
        ids, ips, keys = set(), set(), set()
        for row in payload["routes"]:
            r = Route(row["routerId"], row["tenantId"], row["port"], row["tunnelIp"], row["peerKey"], row["revision"])
            if any(type(v) is not int or v <= 0 for v in (r.router_id, r.tenant_id, r.port, r.revision)):
                raise ValueError("Invalid route identifiers")
            address = ipaddress.IPv4Address(r.tunnel_ip)
            if address not in self.network or address in (self.network.network_address, self.network.broadcast_address) or str(address) == self.hub_ip or not 30000 <= r.port <= 39999:
                raise ValueError("Route outside assigned network or port range")
            if len(base64.b64decode(r.peer_key, validate=True)) != 32:
                raise ValueError("Invalid peer identity")
            if r.port in result or r.router_id in ids or r.tunnel_ip in ips or r.peer_key in keys:
                raise ValueError("Ambiguous route mapping")
            result[r.port] = r
            ids.add(r.router_id)
            ips.add(r.tunnel_ip)
            keys.add(r.peer_key)
        return result

    async def peers(self):
        def read():
            output = subprocess.run(["sudo", "-n", "/usr/local/bin/airpairy-winbox-peers", self.interface],
                                    capture_output=True, timeout=3, check=True).stdout.decode()
            result = {}
            for line in output.splitlines():
                key, ip, at = line.split()
                if ip in result:
                    raise ValueError("Ambiguous WireGuard peer mapping")
                result[ip] = (key, int(at))
            return result
        return await asyncio.to_thread(read)

    async def dns_matches(self, hostname):
        records = await asyncio.get_running_loop().getaddrinfo(hostname, None, family=socket.AF_UNSPEC)
        addresses = {r[4][0] for r in records}
        return addresses == {self.public_ip}

    async def probe(self, route):
        reader, writer = await asyncio.wait_for(asyncio.open_connection(route.tunnel_ip, 8291,
                                                 local_addr=(self.hub_ip, 0)), 3)
        writer.close()
        await writer.wait_closed()

    async def withdraw(self, port):
        self.routes.pop(port, None)
        server = self.servers.pop(port, None)
        if server:
            server.close()
        jobs = list(self.connections.pop(port, set()))
        for job in jobs:
            job.cancel()
        if jobs:
            await asyncio.gather(*jobs, return_exceptions=True)
        if server:
            await server.wait_closed()

    async def clear(self):
        for port in list(self.servers):
            await self.withdraw(port)

    async def reconcile(self, payload):
        desired = self.validate(payload)
        peers = await self.peers()
        dns_ok = await self.dns_matches(payload["publicHost"])
        for port, old in list(self.routes.items()):
            if desired.get(port) != old:
                await self.withdraw(port)
        self.deadline = time.monotonic() + 15
        slots = asyncio.Semaphore(32)
        async def inspect(route):
            peer = peers.get(route.tunnel_ip + "/32")
            if not dns_ok:
                return "DNS_FAILED"
            if not peer or peer[0] != route.peer_key or not 0 <= time.time() - peer[1] <= 180:
                return "OFFLINE"
            async with slots:
                try:
                    await self.probe(route)
                except (OSError, asyncio.TimeoutError):
                    return "OFFLINE"
                try:
                    if route.port not in self.servers:
                        self.servers[route.port] = await asyncio.start_server(lambda r, w, p=route.port: self.connect(p, r, w), self.bind_ip, route.port)
                    self.routes[route.port] = route
                    return "READY"
                except OSError:
                    return "FORWARDING_FAILED"
        async def check(route):
            try:
                result = await asyncio.wait_for(inspect(route), 8)
            except asyncio.TimeoutError:
                result = "CHECK_FAILED"
            if result != "READY":
                await self.withdraw(route.port)
            return {"routerId": route.router_id, "revision": route.revision, "result": result}
        ordered = sorted(desired.values(), key=lambda r: r.port not in self.routes)
        return await asyncio.gather(*(check(route) for route in ordered))

    def admit(self, ip):
        now = time.monotonic()
        if len(self.attempts) > 10000:
            self.attempts = {key: value for key, value in self.attempts.items() if value and value[-1] > now - 60}
            if len(self.attempts) > 10000:
                return False
        recent = self.attempts.setdefault(ip, collections.deque())
        while recent and recent[0] < now - 60:
            recent.popleft()
        if len(recent) >= 10 or self.per_ip.get(ip, 0) >= 4 or sum(self.per_ip.values()) >= 128:
            return False
        recent.append(now)
        return True

    async def connect(self, port, reader, writer):
        address = writer.get_extra_info("peername")
        ip = address[0] if address else "unknown"
        route = self.routes.get(port)
        if not route or time.monotonic() >= self.deadline or not self.admit(ip) or len(self.connections[port]) >= 16:
            writer.close()
            await writer.wait_closed()
            return
        job = asyncio.current_task()
        self.connections[port].add(job)
        self.per_ip[ip] += 1
        remote = None
        try:
            target, remote = await asyncio.wait_for(asyncio.open_connection(route.tunnel_ip, 8291,
                                                   local_addr=(self.hub_ip, 0)), 3)
            async def pump(source, destination):
                while data := await asyncio.wait_for(source.read(65536), 300):
                    destination.write(data)
                    await destination.drain()
            uploads = [asyncio.create_task(pump(reader, remote)), asyncio.create_task(pump(target, writer))]
            try:
                await asyncio.wait(uploads, return_when=asyncio.FIRST_COMPLETED)
            finally:
                for task in uploads:
                    task.cancel()
                await asyncio.gather(*uploads, return_exceptions=True)
        except (OSError, asyncio.TimeoutError):
            pass
        finally:
            self.connections[port].discard(job)
            self.per_ip[ip] -= 1
            if self.per_ip[ip] == 0:
                del self.per_ip[ip]
            writer.transport.abort()
            if remote:
                remote.transport.abort()
            await asyncio.gather(writer.wait_closed(), *([remote.wait_closed()] if remote else []), return_exceptions=True)

    async def expire(self):
        while True:
            if self.servers and time.monotonic() >= self.deadline:
                await self.clear()
            await asyncio.sleep(0.5)


async def main():
    base = os.environ["WINBOX_CONTROL_URL"].rstrip("/")
    if not base.startswith("https://"):
        raise ValueError("HTTPS control URL is required")
    token = os.environ["WINBOX_GATEWAY_TOKEN"]
    if len(token) < 32:
        raise ValueError("A dedicated gateway token is required")
    if not ipaddress.IPv4Address(os.environ["WINBOX_PUBLIC_IP"]).is_global:
        raise ValueError("The relay requires a globally routable public IPv4 address")
    relay = Relay(os.environ["WINBOX_WG_SUBNET"], os.environ["WINBOX_HUB_IP"],
                  os.environ.get("WINBOX_BIND_IP", "0.0.0.0"), os.environ["WINBOX_PUBLIC_IP"],
                  os.environ.get("WINBOX_WG_INTERFACE", "wg0"))
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    def request(path, data=None):
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(base + "/api/internal/winbox/" + path, data=body,
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with opener.open(req, timeout=4) as response:
            payload = response.read(4194305)
            if len(payload) > 4194304:
                raise ValueError("Control payload exceeds limit")
            return json.loads(payload) if payload else None
    expiry = asyncio.create_task(relay.expire())
    try:
        while True:
            try:
                payload = await asyncio.to_thread(request, "routes")
                checks = await asyncio.wait_for(relay.reconcile(payload), 10)
                for offset in range(0, len(checks), 1000):
                    await asyncio.to_thread(request, "checks", checks[offset:offset + 1000])
            except Exception:
                await relay.clear()
                print("WinBox relay control/check unavailable; public mappings withdrawn", flush=True)
            await asyncio.sleep(3)
    finally:
        expiry.cancel()
        await relay.clear()


if __name__ == "__main__":
    asyncio.run(main())
