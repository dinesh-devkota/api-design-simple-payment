package com.customercare.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis infrastructure configuration.
 *
 * <p>{@code RedisConnectionFactory} is auto-configured by Spring Boot using
 * {@code spring.data.redis.*} properties from {@code application.yml} /
 * {@code application-local.yml} / {@code application-prod.yml}.
 *
 * <p>The custom {@link RedisTemplate} uses JSON for values so that any
 * manual {@code redisTemplate.opsForValue()} calls produce human-readable data.
 * Spring Data Redis {@code @RedisHash} repositories use their own hash mapper
 * and do not depend on this template.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}

