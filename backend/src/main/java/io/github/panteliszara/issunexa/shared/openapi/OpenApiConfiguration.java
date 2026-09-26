package io.github.panteliszara.issunexa.shared.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    public OpenAPI issunexaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Issunexa API")
                .description("Session authentication and Ticket creation, retrieval, search and status transitions. "
                        + "Unsafe requests require the CSRF token returned by GET /api/auth/csrf.")
                .version("0.0.1-SNAPSHOT"))
                .components(new Components().addSecuritySchemes("sessionAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")
                        .description("HTTP session cookie established by POST /api/auth/login.")));
    }

    @Bean
    public OpenApiCustomizer ticketContractSchemas() {
        return openApi -> {
            Schema<?> problemDetail = openApi.getComponents().getSchemas().get("ProblemDetail");
            // Spring serializes extensions at the top level, not inside its internal properties map.
            problemDetail.getProperties().remove("properties");
            problemDetail.setAdditionalProperties(true);
            problemDetail.setDescription("RFC 9457 Problem Detail. Validation failures include an errors array "
                    + "with field and message entries.");

            // @Size's default minimum must not obscure the existing @NotBlank constraint.
            Schema<?> createTicketRequest = openApi.getComponents().getSchemas().get("CreateTicketRequest");
            createTicketRequest.getProperties().get("title").setMinLength(1);

            openApi.getPaths().get("/api/tickets").getGet().getParameters().stream()
                    .filter(parameter -> parameter.getName().equals("q"))
                    .forEach(parameter -> {
                        // Java's whitespace regex is not portable to OpenAPI/JavaScript regex engines.
                        parameter.getSchema().setPattern(null);
                        parameter.getSchema().setMinLength(1);
                    });
        };
    }

}
