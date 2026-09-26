package io.github.panteliszara.issunexa.shared.openapi;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OpenApiIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsExistingTicketOperationsAndResponses() throws Exception {
        DocumentContext api = apiDocs();
        Map<String, Object> paths = api.read("$.paths");
        assertThat(paths).containsOnlyKeys("/api/tickets", "/api/tickets/{id}", "/api/tickets/{id}/status",
                "/api/auth/csrf", "/api/auth/login", "/api/auth/logout");
        Map<String, Object> collection = api.read("$.paths['/api/tickets']");
        assertThat(collection).containsOnlyKeys("get", "post");
        Map<String, Object> ticket = api.read("$.paths['/api/tickets/{id}']");
        assertThat(ticket).containsOnlyKeys("get");
        Map<String, Object> statusChange = api.read("$.paths['/api/tickets/{id}/status']");
        assertThat(statusChange).containsOnlyKeys("patch");

        assertResponses(api, "$.paths['/api/tickets'].post", "201", "TicketResponse", "400", "401", "403");
        assertResponses(api, "$.paths['/api/tickets'].get", "200", "TicketPageResponse", "400", "401");
        assertResponses(api, "$.paths['/api/tickets/{id}'].get", "200", "TicketResponse", "400", "401", "404");
        assertResponses(api, "$.paths['/api/tickets/{id}/status'].patch", "200", "TicketResponse",
                "400", "401", "403", "404", "409");
    }

    @Test
    void documentsAuthenticationOperationsAndSafeSchemas() throws Exception {
        DocumentContext api = apiDocs();
        Map<String, Object> csrfPath = api.read("$.paths['/api/auth/csrf']");
        Map<String, Object> loginPath = api.read("$.paths['/api/auth/login']");
        Map<String, Object> logoutPath = api.read("$.paths['/api/auth/logout']");
        assertThat(csrfPath).containsOnlyKeys("get");
        assertThat(loginPath).containsOnlyKeys("post");
        assertThat(logoutPath).containsOnlyKeys("post");
        assertResponses(api, "$.paths['/api/auth/csrf'].get", "200", "CsrfTokenResponse");
        Map<String, Object> csrfProperties = api.read("$.components.schemas.CsrfTokenResponse.properties");
        assertThat(csrfProperties).containsOnlyKeys("token", "headerName");
        assertThat(api.read("$.paths['/api/auth/login'].post.requestBody.content['application/json'].schema['$ref']",
                String.class)).isEqualTo("#/components/schemas/LoginRequest");
        Map<String, Object> loginProperties = api.read("$.components.schemas.LoginRequest.properties");
        assertThat(loginProperties).containsOnlyKeys("email", "password");
        List<String> requiredFields = api.read("$.components.schemas.LoginRequest.required");
        assertThat(requiredFields).containsExactlyInAnyOrder("email", "password");
        assertThat(api.read("$.components.schemas.LoginRequest.properties.email.maxLength", Integer.class))
                .isEqualTo(254);
        assertThat(api.read("$.components.schemas.LoginRequest.properties.email.minLength", Integer.class)).isEqualTo(1);
        assertThat(api.read("$.components.schemas.LoginRequest.properties.password.minLength", Integer.class))
                .isEqualTo(1);
        assertThat(api.read("$.components.schemas.LoginRequest.properties.password.writeOnly", Boolean.class)).isTrue();
        assertThat(api.read("$.components.schemas.LoginRequest.properties.password.format", String.class))
                .isEqualTo("password");
        for (String path : List.of("/api/auth/login", "/api/auth/logout")) {
            Map<String, Object> success = api.read("$.paths['" + path + "'].post.responses['204']");
            assertThat(success).doesNotContainKey("content");
            for (String code : path.endsWith("login") ? List.of("400", "401", "403") : List.of("401", "403")) {
                assertThat(api.read("$.paths['" + path + "'].post.responses['" + code
                        + "'].content['application/problem+json'].schema['$ref']", String.class))
                        .isEqualTo("#/components/schemas/ProblemDetail");
            }
        }
    }

    @Test
    void documentsSessionCookieRequirementsAndCsrfHeaders() throws Exception {
        DocumentContext api = apiDocs();
        Map<String, Object> schemes = api.read("$.components.securitySchemes");
        assertThat(schemes).containsOnlyKeys("sessionAuth");
        Map<String, Object> sessionScheme = api.read("$.components.securitySchemes.sessionAuth");
        assertThat(sessionScheme).containsEntry("type", "apiKey").containsEntry("in", "cookie")
                .containsEntry("name", "JSESSIONID");
        for (String operation : List.of("$.paths['/api/tickets'].get", "$.paths['/api/tickets'].post",
                "$.paths['/api/tickets/{id}'].get", "$.paths['/api/tickets/{id}/status'].patch",
                "$.paths['/api/auth/logout'].post")) {
            List<Map<String, Object>> security = api.read(operation + ".security");
            assertThat(security).containsExactly(Map.of("sessionAuth", List.of()));
        }
        for (String operation : List.of("$.paths['/api/auth/csrf'].get", "$.paths['/api/auth/login'].post")) {
            Map<String, Object> publicOperation = api.read(operation);
            assertThat(publicOperation).doesNotContainKey("security");
        }
        for (String operation : List.of("$.paths['/api/auth/login'].post", "$.paths['/api/auth/logout'].post",
                "$.paths['/api/tickets'].post", "$.paths['/api/tickets/{id}/status'].patch")) {
            List<Map<String, Object>> headers = api.read(operation
                    + ".parameters[?(@.name == 'X-CSRF-TOKEN')]");
            assertThat(headers).singleElement().satisfies(header ->
                    assertThat(header).containsEntry("in", "header").containsEntry("required", true));
        }
    }

    @Test
    void documentsPublicRequestAndPaginationSchemas() throws Exception {
        DocumentContext api = apiDocs();
        Map<String, Object> create = api.read("$.components.schemas.CreateTicketRequest.properties");
        assertThat(create).containsOnlyKeys("title", "description", "priority");
        assertThat(api.read("$.components.schemas.CreateTicketRequest.properties.title.minLength", Integer.class))
                .isEqualTo(1);
        Map<String, Object> update = api.read("$.components.schemas.UpdateTicketStatusRequest.properties");
        assertThat(update).containsOnlyKeys("status");
        Map<String, Object> page = api.read("$.components.schemas.TicketPageResponse.properties");
        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages", "first", "last");
        assertThat(api.read("$.components.schemas.TicketPageResponse.properties.content.items['$ref']", String.class))
                .isEqualTo("#/components/schemas/TicketResponse");
        Map<String, Object> problem = api.read("$.components.schemas.ProblemDetail.properties");
        assertThat(problem).containsOnlyKeys("type", "title", "status", "detail", "instance");
        assertThat(api.read("$.components.schemas.ProblemDetail.additionalProperties", Boolean.class)).isTrue();
        assertThat(api.read("$.components.schemas.ProblemDetail.description", String.class)).contains("errors");
    }

    @Test
    void documentsListingParametersWithPublicValuesAndLimits() throws Exception {
        DocumentContext api = apiDocs();
        List<Map<String, Object>> parameters = api.read("$.paths['/api/tickets'].get.parameters");
        assertThat(parameters).extracting(parameter -> parameter.get("name"))
                .containsExactlyInAnyOrder("page", "size", "status", "priority", "sortBy", "direction", "q");
        assertThat(parameters).allSatisfy(parameter ->
                assertThat(parameter).containsEntry("in", "query").containsEntry("required", false));

        assertThat(parameterSchema(api, "page")).containsEntry("default", 0).containsEntry("minimum", 0);
        assertThat(parameterSchema(api, "size"))
                .containsEntry("default", 20).containsEntry("minimum", 1).containsEntry("maximum", 100);
        assertThat(parameterSchema(api, "q"))
                .containsEntry("minLength", 1).containsEntry("maxLength", 100).doesNotContainKey("pattern");
        assertThat(parameterSchema(api, "sortBy")).containsEntry("default", "createdAt");
        assertThat(parameterSchema(api, "direction")).containsEntry("default", "desc");
        assertParameterValues(api, "status", "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
        assertParameterValues(api, "priority", "LOW", "MEDIUM", "HIGH", "URGENT");
        assertParameterValues(api, "sortBy", "createdAt", "updatedAt", "title");
        assertParameterValues(api, "direction", "asc", "desc");
    }

    @Test
    void servesSwaggerUiAndPointsItToTheGeneratedSpecification() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Swagger UI")));

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    private DocumentContext apiDocs() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Issunexa API"))
                .andExpect(jsonPath("$.info.version").value("0.0.1-SNAPSHOT"))
                .andExpect(jsonPath("$.security").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("UserAccount", "passwordHash", "password_hash")
                .doesNotContainIgnoringCase("bearer", "jwt");
        return JsonPath.parse(json);
    }

    private static void assertResponses(DocumentContext api, String operation, String successCode,
            String successSchema, String... errorCodes) {
        Map<String, Object> responses = api.read(operation + ".responses");
        assertThat(responses).containsKey(successCode);
        assertThat(api.read(operation + ".responses['" + successCode
                + "'].content['application/json'].schema['$ref']", String.class))
                .isEqualTo("#/components/schemas/" + successSchema);
        for (String errorCode : errorCodes) {
            assertThat(responses).containsKey(errorCode);
            assertThat(api.read(operation + ".responses['" + errorCode
                    + "'].content['application/problem+json'].schema['$ref']", String.class))
                    .isEqualTo("#/components/schemas/ProblemDetail");
        }
    }

    private static String parameterPath(String name) {
        return "$.paths['/api/tickets'].get.parameters[?(@.name == '" + name + "')]";
    }

    private static Map<String, Object> parameterSchema(DocumentContext api, String name) {
        List<Map<String, Object>> schemas = api.read(parameterPath(name) + ".schema");
        assertThat(schemas).hasSize(1);
        return schemas.getFirst();
    }

    private static void assertParameterValues(DocumentContext api, String name, String... expectedValues) {
        List<List<String>> values = api.read(parameterPath(name) + ".schema.enum");
        assertThat(values).hasSize(1);
        assertThat(values.getFirst()).containsExactlyInAnyOrder(expectedValues);
    }

}
