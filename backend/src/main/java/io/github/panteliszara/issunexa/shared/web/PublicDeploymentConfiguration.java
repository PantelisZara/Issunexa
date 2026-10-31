package io.github.panteliszara.issunexa.shared.web;

import io.github.panteliszara.issunexa.auth.LoginSourceResolver;
import io.github.panteliszara.issunexa.auth.LoginThrottleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@Profile("public")
@EnableConfigurationProperties(PublicDeploymentProperties.class)
public class PublicDeploymentConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicHttpsConnector(
            PublicDeploymentProperties deployment, ServerProperties server, Environment environment) {
        validateContract(server, environment);
        return factory -> factory.addConnectorCustomizers(connector -> {
            // The dedicated ingress terminates TLS. Never derive these values from request headers.
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setProxyName(deployment.origin().getHost());
            connector.setProxyPort(443);
        });
    }

    static void validateContract(ServerProperties server, Environment environment) {
        var session = server.getServlet().getSession();
        var cookie = session.getCookie();
        if (server.getForwardHeadersStrategy() != ServerProperties.ForwardHeadersStrategy.NONE
                || !Boolean.TRUE.equals(cookie.getSecure()) || !Boolean.TRUE.equals(cookie.getHttpOnly())
                || cookie.getSameSite() != Cookie.SameSite.LAX || cookie.getDomain() != null
                || !"/".equals(cookie.getPath())
                || !Set.of(Session.SessionTrackingMode.COOKIE).equals(session.getTrackingModes())
                || session.getTimeout() == null || session.getTimeout().isZero() || session.getTimeout().isNegative()
                || session.getTimeout().compareTo(Duration.ofMinutes(20)) > 0
                || environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)
                || environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true)) {
            throw new IllegalArgumentException("Public HTTPS, session and explorer settings must preserve the security contract.");
        }
    }

    @Bean
    public LoginSourceResolver publicLoginSourceResolver(
            PublicDeploymentProperties deployment, LoginThrottleProperties throttle) {
        if (!throttle.trustedProxy().isBlank()) {
            throw new IllegalArgumentException("Public ingress cannot also select the Docker login proxy.");
        }
        return deployment.ingress() == PublicDeploymentProperties.Ingress.RENDER_FREE
                ? LoginSourceResolver.renderFreeIngress() : new LoginSourceResolver(throttle);
    }
}
