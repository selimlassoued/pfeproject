package com.recrutment.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.context.SecurityContextHolder;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class ApplicationMicroserviceApplication {

    public static void main(String[] args) {
        // Make @Async tasks inherit the calling thread's SecurityContext so
        // they can call protected service endpoints (job-microservice) with
        // the user's JWT.
        //
        // Without this, the analyzeAsync() task that orchestrates CV analysis
        // -> semantic match runs on a background pool thread with an EMPTY
        // SecurityContext. The auth interceptor on the @LoadBalanced
        // RestTemplate has no JWT to attach, JobClient gets 401 from
        // job-microservice, the matcher is silently skipped, and the
        // application shows "Semantic matching is processing..." forever.
        //
        // INHERITABLETHREADLOCAL = child threads spawned from a thread
        // that has a SecurityContext inherit it. Spring's @Async pool
        // creates child threads from the request thread, so the user's
        // JWT propagates.
        //
        // Caveat: pool threads outlive single requests so the previous
        // user's context can hang around on a reused pool thread. For
        // PFE-scale traffic this is fine; in production we'd switch to
        // DelegatingSecurityContextAsyncTaskExecutor for explicit per-task
        // propagation.
        SecurityContextHolder.setStrategyName(
                SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);

        SpringApplication.run(ApplicationMicroserviceApplication.class, args);
    }

}
