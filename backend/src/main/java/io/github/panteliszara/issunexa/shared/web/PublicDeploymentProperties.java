package io.github.panteliszara.issunexa.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

@ConfigurationProperties("issunexa.public-deployment")
public record PublicDeploymentProperties(URI origin, @DefaultValue("PEER") Ingress ingress) {

    public PublicDeploymentProperties {
        if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || (origin.getPort() != -1 && origin.getPort() != 443)
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Public deployment requires a canonical HTTPS origin.");
        }
        if (ingress == null) throw new IllegalArgumentException("Public ingress must be selected.");
        if (ingress == Ingress.RENDER_FREE && !origin.getHost().endsWith(".onrender.com")) {
            throw new IllegalArgumentException("Render Free ingress requires an onrender.com origin.");
        }
    }

    public enum Ingress {
        PEER, RENDER_FREE
    }
}
