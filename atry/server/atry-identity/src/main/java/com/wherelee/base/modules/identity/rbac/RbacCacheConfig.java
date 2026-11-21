package com.wherelee.base.modules.identity.rbac;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** RBAC 缓存失效的 Redis 订阅（多实例，同一频道）。 */
@Configuration(proxyBeanMethods = false)
public class RbacCacheConfig {

    @Bean
    public RedisMessageListenerContainer rbacCacheListenerContainer(
            RedisConnectionFactory connectionFactory,
            RbacCacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RbacCacheChannels.CHANNEL));
        return container;
    }
}
