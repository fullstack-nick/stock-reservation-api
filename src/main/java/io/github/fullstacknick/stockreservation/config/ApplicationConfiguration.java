package io.github.fullstacknick.stockreservation.config;

import io.github.fullstacknick.stockreservation.reservation.domain.AllocationPlanner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    @Bean AllocationPlanner allocationPlanner() { return new AllocationPlanner(); }

    @Bean @Profile("!demo")
    Clock systemClock(@Value("${warehouse.zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean @Profile("demo")
    Clock demoClock(@Value("${warehouse.zone}") String zone) {
        var clock = Clock.fixed(Instant.parse("2026-11-01T09:00:00Z"), ZoneId.of(zone));
        LoggerFactory.getLogger(ApplicationConfiguration.class)
                .info("Demo business clock is fixed at {} in {}", clock.instant(), clock.getZone());
        return clock;
    }

    @Bean
    JsonMapperBuilderCustomizer strictJson() {
        return builder -> builder
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
