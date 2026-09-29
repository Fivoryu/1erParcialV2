package com.diagramador.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class EditorCorsConfig implements WebMvcConfigurer {

    private static final String EDITOR_ORIGIN = "http://localhost:5173";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(EDITOR_ORIGIN)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Accept", "Authorization", "Content-Type")
                .maxAge(3600);
    }
}
