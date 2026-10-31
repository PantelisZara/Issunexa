package io.github.panteliszara.issunexa.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;

@Component
@Profile("!public")
public class LoginSourceResolver {

    private final String trustedProxy;
    private final ProxyAddresses proxyAddresses;
    private final boolean renderFreeIngress;

    @Autowired
    public LoginSourceResolver(LoginThrottleProperties policy) {
        this(policy.trustedProxy(), InetAddress::getAllByName);
    }

    LoginSourceResolver(String trustedProxy, ProxyAddresses proxyAddresses) {
        this(trustedProxy, proxyAddresses, false);
    }

    private LoginSourceResolver(String trustedProxy, ProxyAddresses proxyAddresses, boolean renderFreeIngress) {
        this.trustedProxy = trustedProxy;
        this.proxyAddresses = proxyAddresses;
        this.renderFreeIngress = renderFreeIngress;
    }

    public static LoginSourceResolver renderFreeIngress() {
        return new LoginSourceResolver("", InetAddress::getAllByName, true);
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        try {
            if (renderFreeIngress) return headerOrPeer(request, "CF-Connecting-IP", peer);
            if (trustedProxy.isBlank()) return peer;
            InetAddress peerAddress = literal(peer);
            if (peerAddress == null || Arrays.stream(proxyAddresses.resolve(trustedProxy))
                    .noneMatch(peerAddress::equals)) return peer;
            return headerOrPeer(request, "X-Real-IP", peer);
        } catch (UnknownHostException exception) {
            // Invalid addresses or failed proxy DNS use the peer budget, never an unverified header.
            return peer;
        }
    }

    private static String headerOrPeer(HttpServletRequest request, String header, String peer)
            throws UnknownHostException {
        var headers = Collections.list(request.getHeaders(header));
        if (headers.size() != 1) return peer;
        InetAddress client = literal(headers.getFirst());
        return client == null ? peer : client.getHostAddress();
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
