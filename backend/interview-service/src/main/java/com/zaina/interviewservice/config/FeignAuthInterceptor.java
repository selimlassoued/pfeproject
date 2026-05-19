package com.zaina.interviewservice.config;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Slf4j
public class FeignAuthInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String auth = attrs.getRequest().getHeader("Authorization");
            if (auth != null) {
                template.header("Authorization", auth);
                log.debug("Feign — forwarding Authorization header");
            } else {
                log.warn("Feign — no Authorization header found in current request");
            }
        } else {
            log.warn("Feign — no request context (async thread?), cannot forward auth");
        }
    }
}