package com.whut.map.map_service.llm.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

@Component
public class PromptTemplateService {

    private static final String DEFAULT_PROMPTS_PATH = "prompts";

    private final Map<PromptScene, String> systemPrompts;

    public PromptTemplateService() {
        this(DEFAULT_PROMPTS_PATH);
    }

    // 启动时一次性加载所有prompt模板
    PromptTemplateService(String promptsPath) {
        EnumMap<PromptScene, String> templates = new EnumMap<>(PromptScene.class);
        for (PromptScene scene : PromptScene.values()) {
            templates.put(scene, loadTemplate(promptsPath, scene));
        }
        // 使用copyOf防止外部修改
        this.systemPrompts = Map.copyOf(templates);
    }

    public String getSystemPrompt(PromptScene scene) {
        String prompt = systemPrompts.get(scene);
        if (prompt == null) {
            throw new IllegalArgumentException("Unsupported prompt scene: " + scene);
        }
        return prompt;
    }

    private String loadTemplate(String promptsPath, PromptScene scene) {
        String resourcePath = promptsPath + "/" + scene.resourceName();
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing prompt template resource: " + resourcePath);
        }

        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("Prompt template resource is blank: " + resourcePath);
            }
            return content.strip();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load prompt template resource: " + resourcePath, exception);
        }
    }
}
