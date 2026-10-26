package com.whut.map.map_service.navigation.notice;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.navigation.config.NavigationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 加载 notice-pipeline 推演好的航标状态；运行时不调用 LLM，也不再推演状态。
 */
@Slf4j
@Component
public class AtonNoticeRepository {

    private final AtonNoticeDataset dataset;
    private final List<AtonRecord> atons;
    private final Map<String, AtonNoticeDataset.NoticeMeta> noticesById;

    public AtonNoticeRepository(
            NavigationProperties properties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper
    ) {
        String location = properties.getNotice().getAtonStateResource();
        ObjectMapper reader = objectMapper.copy()
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            this.dataset = reader.readValue(in, AtonNoticeDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load aid-to-navigation state from " + location, e);
        }
        this.atons = accept(dataset.atons());
        this.noticesById = (dataset.notices() == null ? List.<AtonNoticeDataset.NoticeMeta>of() : dataset.notices())
                .stream()
                .collect(Collectors.toUnmodifiableMap(AtonNoticeDataset.NoticeMeta::noticeId, Function.identity()));
        log.info("Loaded {} aids to navigation ({} valid), data cutoff {}",
                atons.size(), validAtons().size(), dataCutoff());
    }

    static List<AtonRecord> accept(List<AtonRecord> candidates) {
        List<AtonRecord> accepted = new ArrayList<>();
        for (AtonRecord aton : candidates == null ? List.<AtonRecord>of() : candidates) {
            if (aton.state() == null) {
                log.warn("Skip aid {} with unknown state", aton.atonId());
                continue;
            }
            if (aton.position() == null) {
                throw new IllegalStateException("Aid " + aton.atonId() + " has no position");
            }
            accepted.add(aton);
        }
        return List.copyOf(accepted);
    }

    public AtonNoticeDataset dataset() {
        return dataset;
    }

    public List<AtonRecord> atons() {
        return atons;
    }

    public List<AtonRecord> validAtons() {
        return atons.stream().filter(aton -> aton.state() == AtonState.VALID).toList();
    }

    public Optional<AtonNoticeDataset.NoticeMeta> findNotice(String noticeId) {
        return Optional.ofNullable(noticesById.get(noticeId));
    }

    public String dataCutoff() {
        return dataset.coverage() == null ? null : dataset.coverage().publishedTo();
    }
}
