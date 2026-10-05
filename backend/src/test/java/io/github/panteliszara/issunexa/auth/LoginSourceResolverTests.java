package io.github.panteliszara.issunexa.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSourceResolverTests {

    @Test
    void directRequestsIgnoreAllClientSuppliedSourceHeaders() throws Exception {
        var request = request("192.0.2.1", "203.0.113.1");
        request.addHeader("X-Forwarded-For", "203.0.113.2");
        request.addHeader("Forwarded", "for=203.0.113.3");
        assertThat(resolver("").resolve(request)).isEqualTo("192.0.2.1");
        assertThat(resolver("frontend").resolve(request)).isEqualTo("192.0.2.1");
    }

    @Test
    void trustsOnlyTheConfiguredProxyPeerAndCanonicalizesItsSingleLiteralHeader() throws Exception {
        var resolver = resolver("frontend");
        assertThat(resolver.resolve(request("172.20.0.2", "203.0.113.1"))).isEqualTo("203.0.113.1");
        assertThat(resolver.resolve(request("172.20.0.2", "2001:db8::1")))
                .isEqualTo(InetAddress.getByName("2001:db8::1").getHostAddress());
        assertThat(resolver.resolve(request("172.20.0.3", "203.0.113.1"))).isEqualTo("172.20.0.3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "203.0.113.1, 203.0.113.2", "203.0.113.1:80", "", "999.0.0.1",
            "2001:db8::1%eth0", "abc", "[2001:db8::1]"})
    void malformedProxyHeadersFallBackToPeerWithoutTrustingForwardedChains(String header) throws Exception {
        assertThat(resolver("frontend").resolve(request("172.20.0.2", header))).isEqualTo("172.20.0.2");
    }

    @Test
    void missingAndDuplicateHeadersUseSharedPeerBudget() throws Exception {
        var request = request("172.20.0.2", null);
        assertThat(resolver("frontend").resolve(request)).isEqualTo("172.20.0.2");
        request.addHeader("X-Real-IP", "203.0.113.1");
        request.addHeader("X-Real-IP", "203.0.113.2");
        assertThat(resolver("frontend").resolve(request)).isEqualTo("172.20.0.2");
    }

    @Test
    void proxyDnsFailureUsesSharedPeerBudget() {
        var resolver = new LoginSourceResolver("frontend", name -> { throw new UnknownHostException(); });
        assertThat(resolver.resolve(request("172.20.0.2", "203.0.113.1"))).isEqualTo("172.20.0.2");
    }

    @Test
    void refreshesConfiguredProxyAddressesForContainerReplacement() throws Exception {
        InetAddress[][] addresses = { { InetAddress.getByName("172.20.0.2") } };
        var resolver = new LoginSourceResolver("frontend", name -> addresses[0]);
        assertThat(resolver.resolve(request("172.20.0.2", "203.0.113.1"))).isEqualTo("203.0.113.1");
        addresses[0] = new InetAddress[] { InetAddress.getByName("172.20.0.4") };
        assertThat(resolver.resolve(request("172.20.0.2", "203.0.113.1"))).isEqualTo("172.20.0.2");
        assertThat(resolver.resolve(request("172.20.0.4", "203.0.113.1"))).isEqualTo("203.0.113.1");
    }

    private static LoginSourceResolver resolver(String trustedProxy) throws UnknownHostException {
        var proxy = InetAddress.getByName("172.20.0.2");
        return new LoginSourceResolver(trustedProxy, name -> new InetAddress[] { proxy });
    }

    private static MockHttpServletRequest request(String peer, String header) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (header != null) request.addHeader("X-Real-IP", header);
        return request;
    }
}
