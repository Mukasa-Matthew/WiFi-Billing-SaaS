package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands out the lowest free host address in the tunnel subnet, skipping the
 * network address, the hub (radius.server.ip, normally .0.1) and broadcast.
 * The unique constraint on routers.wg_ip is the final guard against races.
 */
@Service
@RequiredArgsConstructor
public class WireGuardIpAllocator {

    private final RouterRepository routerRepository;
    private final PlatformProperties props;

    public synchronized String allocate() {
        Ipv4Subnet subnet = Ipv4Subnet.parse(props.getWgSubnet());
        Set<String> used = new HashSet<>();
        for (String ip : routerRepository.findAllAllocatedWgIps()) {
            used.add(Ipv4Subnet.stripPrefix(ip));
        }
        used.add(props.getRadiusServerIp());

        for (long a = subnet.network() + 1; a < subnet.broadcast(); a++) {
            String candidate = Ipv4Subnet.toIp(a);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "WireGuard subnet exhausted");
    }
}
