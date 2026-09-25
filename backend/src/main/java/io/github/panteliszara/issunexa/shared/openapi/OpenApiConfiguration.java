package io.github.panteliszara.issunexa.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    public OpenAPI issunexaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Issunexa API")
                .description("Ticket creation, retrieval, paginated search and controlled status transitions.")
                .version("0.0.1-SNAPSHOT"));
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
