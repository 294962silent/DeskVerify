package com.echomind.api;

import java.net.URI;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SwaggerDocsController {

    // Pin this path to the Swagger UI WebJar version in pom.xml.
    private static final String SWAGGER_ASSETS = "META-INF/resources/webjars/swagger-ui/5.32.2/";

    @GetMapping("/swagger-ui.html")
    public ResponseEntity<Void> swaggerUiRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("swagger-ui/index.html"))
                .build();
    }

    @GetMapping(value = "/docs", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String docs() {
        return render("v3/api-docs", "swagger-ui/assets");
    }

    @GetMapping(value = "/swagger-ui/index.html", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String swaggerUi() {
        return render("../v3/api-docs", "assets");
    }

    @GetMapping(value = "/swagger-ui/assets/swagger-ui.css", produces = "text/css")
    @ResponseBody
    public Resource stylesheet() {
        return new ClassPathResource(SWAGGER_ASSETS + "swagger-ui.css");
    }

    @GetMapping(value = "/swagger-ui/assets/swagger-ui-bundle.js", produces = "application/javascript")
    @ResponseBody
    public Resource bundle() {
        return new ClassPathResource(SWAGGER_ASSETS + "swagger-ui-bundle.js");
    }

    private String render(String specPath, String assetPath) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>EchoMind Java API Docs</title>
                  <link rel="stylesheet" href="%s/swagger-ui.css">
                  <style>
                    body { margin: 0; background: #f7f7f7; }
                    .topbar { display: none; }
                  </style>
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="%s/swagger-ui-bundle.js"></script>
                  <script>
                    window.onload = function () {
                      window.ui = SwaggerUIBundle({
                        url: new URL("%s", window.location.href).href,
                        dom_id: "#swagger-ui",
                        deepLinking: true,
                        displayOperationId: false,
                        defaultModelsExpandDepth: 1,
                        defaultModelExpandDepth: 1,
                        docExpansion: "list",
                        filter: true,
                        persistAuthorization: false,
                        validatorUrl: null,
                        tryItOutEnabled: true
                      });
                    };
                  </script>
                </body>
                </html>
                """.formatted(assetPath, assetPath, specPath);
    }
}
