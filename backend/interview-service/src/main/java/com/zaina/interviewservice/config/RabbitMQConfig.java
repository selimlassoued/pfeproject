package com.zaina.interviewservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ANALYSIS_QUEUE    = "interview.analysis.queue";
    public static final String ANALYSIS_EXCHANGE = "interview.analysis.exchange";
    public static final String ANALYSIS_ROUTING  = "interview.analysis";

    /** Shared cross-service topic exchange — interview events fan out to notifications here. */
    @Bean
    public TopicExchange appEventsExchange(
            @Value("${app.messaging.exchange:app.events}") String exchangeName) {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue analysisQueue() {
        return QueueBuilder.durable(ANALYSIS_QUEUE).build();
    }

    @Bean
    public DirectExchange analysisExchange() {
        return new DirectExchange(ANALYSIS_EXCHANGE);
    }

    @Bean
    public Binding analysisBinding(Queue analysisQueue, DirectExchange analysisExchange) {
        return BindingBuilder.bind(analysisQueue).to(analysisExchange).with(ANALYSIS_ROUTING);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter jsonMessageConverter) {
        RabbitTemplate tpl = new RabbitTemplate(cf);
        tpl.setMessageConverter(jsonMessageConverter);
        return tpl;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(jsonMessageConverter);
        return factory;
    }
}