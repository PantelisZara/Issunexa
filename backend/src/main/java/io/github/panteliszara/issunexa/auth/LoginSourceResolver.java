package io.github.panteliszara.issunexa.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;

@Component
public class LoginSourceResolver {

    private final String trustedProxy;
    private final ProxyAddresses proxyAddresses;

    @Autowired
    public LoginSourceResolver(LoginThrottleProperties policy) {
        this(policy.trustedProxy(), InetAddress::getAllByName);
    }

    LoginSourceResolver(String trustedProxy, ProxyAddresses proxyAddresses) {
        this.trustedProxy = trustedProxy;
        this.proxyAddresses = proxyAddresses;
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxy.isBlank()) return peer;
        try {
            InetAddress peerAddress = literal(peer);
            if (peerAddress == null || Arrays.stream(proxyAddresses.resolve(trustedProxy))
                    .noneMatch(peerAddress::equals)) return peer;
            var headers = Collections.list(request.getHeaders("X-Real-IP"));
            if (headers.size() != 1) return peer;
            InetAddress client = literal(headers.getFirst());
            return client == null ? peer : client.getHostAddress();
        } catch (UnknownHostException exception) {
            // DNS failure falls back to a shared peer budget, never to an unverified header.
            return peer;
        }
    }

    private static InetAddress literal(String value) throws UnknownHostException {
        if (value == null || value.length() > 45) return null;
        boolean ipv4 = value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
        boolean ipv6 = value.contains(":") && value.matches("[0-9a-fA-F:.]+");
        // Reject names, lists, ports and scoped addresses before any DNS lookup.
        return ipv4 || ipv6 ? InetAddress.getByName(value) : null;
    }

    @FunctionalInterface
    interface ProxyAddresses {
        InetAddress[] resolve(String name) throws UnknownHostException;
    }
}
