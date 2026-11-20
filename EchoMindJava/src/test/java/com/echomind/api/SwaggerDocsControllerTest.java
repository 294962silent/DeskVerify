package com.echomind.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.echomind.config.OpenApiConfig;
import java.net.URI;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SwaggerDocsControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SwaggerDocsController()).build();

    @ParameterizedTest
    @ValueSource(strings = {"/docs", "/swagger-ui/index.html"})
    void swaggerTemplateResolvesOpenApiOnBothOrigins(String path) throws Exception {
        String html = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var url = Pattern.compile("url:\\s*new URL\\(\"([^\"]+)\", window\\.location\\.href\\)\\.href")
                .matcher(html);
        assertThat(url.find()).isTrue();
        assertThat(URI.create("http://localhost:8080" + path).resolve(url.group(1)).getPath())
                .isEqualTo("/v3/api-docs");
        assertThat(URI.create("http://localhost:5173/api/java" + path).resolve(url.group(1)).getPath())
                .isEqualTo("/api/java/v3/api-docs");

        assertThat(html).doesNotContain("cdn.jsdelivr.net")
                .contains("persistAuthorization: false", "validatorUrl: null");
        assertAssetResolvesOnBothOrigins(html, path, "href", "swagger-ui.css");
        assertAssetResolvesOnBothOrigins(html, path, "src", "swagger-ui-bundle.js");
    }

    @ParameterizedTest
    @ValueSource(strings = {"swagger-ui.css", "swagger-ui-bundle.js"})
    void localAssetIsServedFromThePackagedDistribution(String file) throws Exception {
        var result = mvc.perform(get("/swagger-ui/assets/" + file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(file.endsWith(".js") ? "application/javascript" : "text/css"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray().length).isGreaterThan(10_000);
    }

    private static void assertAssetResolvesOnBothOrigins(String html, String path, String attribute, String file) {
        var url = Pattern.compile(attribute + "=\"([^\"]*" + Pattern.quote(file) + ")\"").matcher(html);
        assertThat(url.find()).isTrue();
        String asset = url.group(1);
        assertThat(URI.create("http://localhost:18080" + path).resolve(asset).toString())
                .isEqualTo("http://localhost:18080/swagger-ui/assets/" + file);
        assertThat(URI.create("http://localhost:15173/api/java" + path).resolve(asset).toString())
                .isEqualTo("http://localhost:15173/api/java/swagger-ui/assets/" + file);
    }

    @Test
    void tryItOutUsesTheOriginAndPrefixOfTheSpecification() {
        var servers = new OpenApiConfig().echoMindOpenAPI().getServers();
        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(URI.create("http://localhost:18080/v3/api-docs")
                    .resolve(server.getUrl()).resolve("health").toString())
                    .isEqualTo("http://localhost:18080/health");
            assertThat(URI.create("http://localhost:15173/api/java/v3/api-docs")
                    .resolve(server.getUrl()).resolve("health").toString())
                    .isEqualTo("http://localhost:15173/api/java/health");
        });
    }

    @Test
    void legacySwaggerRedirectKeepsTheProxyPrefix() throws Exception {
        String location = mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(URI.create("http://localhost:15173/api/java/swagger-ui.html").resolve(location).getPath())
                .isEqualTo("/api/java/swagger-ui/index.html");
    }
}
