package com.recrutment.notificationmicroservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${app.messaging.exchange}")
    private String exchangeName;

    @Value("${app.messaging.notification-queue}")
    private String notificationQueueName;

    @Bean
    public TopicExchange appEventsExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return new Queue(notificationQueueName, true);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange appEventsExchange) {
        // listen to all notify.* routing keys
        return BindingBuilder.bind(notificationQueue)
                .to(appEventsExchange)
                .with("notify.#");
    }
}