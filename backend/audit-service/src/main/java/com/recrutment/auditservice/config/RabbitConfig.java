package com.recrutment.auditservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${app.messaging.exchange}")
    private String exchangeName;

    @Value("${app.messaging.audit-queue}")
    private String auditQueueName;

    @Value("${app.messaging.audit-routing-pattern}")
    private String routingPattern;

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory cf) {
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        return admin;
    }

    // ✅ THIS forces declaration on startup
    @Bean
    public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(auditQueueName).build();
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange auditExchange) {
        return BindingBuilder.bind(auditQueue).to(auditExchange).with(routingPattern);
    }
}
