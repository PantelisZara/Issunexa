package io.github.panteliszara.issunexa.shared.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PublicDeploymentPropertiesTests {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://demo.example", "https://user:password@demo.example", "https://demo.example/path",
            "https://demo.example?query=1", "https://demo.example#fragment", "https://demo.example:8080",
            "https:///missing-host"
    })
    void rejectsNonCanonicalOrigins(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PublicDeploymentProperties(URI.create(origin), PublicDeploymentProperties.Ingress.PEER));
    }

    @Test
    void acceptsOnlyTheExplicitRenderHostContractForRenderIngress() {
        assertThat(new PublicDeploymentProperties(URI.create("https://demo.onrender.com"),
                PublicDeploymentProperties.Ingress.RENDER_FREE).origin().getHost()).isEqualTo("demo.onrender.com");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PublicDeploymentProperties(URI.create("https://onrender.com.attacker.example"),
                        PublicDeploymentProperties.Ingress.RENDER_FREE));
    }

    @Test
    void missingOrInvalidOriginPreventsConfigurationStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("issunexa.public-deployment.origin=http://demo.example")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("issunexa.public-deployment.origin=https://demo.example")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE", "FRAMEWORK"})
    void rejectsEffectiveForwardingOverrides(String strategy) {
        var server = validServer();
        server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.valueOf(strategy));
        assertThatIllegalArgumentException().isThrownBy(() ->
                PublicDeploymentConfiguration.validateContract(server, validEnvironment()));
    }

    @Test
    void rejectsEffectiveCookieOrExplorerOverrides() {
        var server = validServer();
        PublicDeploymentConfiguration.validateContract(server, validEnvironment());
        server.getServlet().getSession().getCookie().setSecure(false);
        assertThatIllegalArgumentException().isThrownBy(() ->
                PublicDeploymentConfiguration.validateContract(server, validEnvironment()));
        var domainCookieServer = validServer();
        domainCookieServer.getServlet().getSession().getCookie().setDomain("example.com");
        assertThatIllegalArgumentException().isThrownBy(() ->
                PublicDeploymentConfiguration.validateContract(domainCookieServer, validEnvironment()));
        var enabledExplorer = validEnvironment().withProperty("springdoc.api-docs.enabled", "true");
        assertThatIllegalArgumentException().isThrownBy(() ->
                PublicDeploymentConfiguration.validateContract(validServer(), enabledExplorer));
    }

    private static ServerProperties validServer() {
        var server = new ServerProperties();
        server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
        var session = server.getServlet().getSession();
        session.setTimeout(Duration.ofMinutes(20));
        session.setTrackingModes(Set.of(Session.SessionTrackingMode.COOKIE));
        var cookie = session.getCookie();
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setSameSite(Cookie.SameSite.LAX);
        cookie.setPath("/");
        return server;
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment().withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PublicDeploymentProperties.class)
    static class PropertiesConfiguration {
    }
}
