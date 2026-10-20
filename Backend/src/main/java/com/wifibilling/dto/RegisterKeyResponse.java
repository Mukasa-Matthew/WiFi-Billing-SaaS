package com.wifibilling.dto;

public record RegisterKeyResponse(String status, String wgIp, boolean hubPeerConfigured) {
}
