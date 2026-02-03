package com.zaina.jobmicroservice;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import com.zaina.jobmicroservice.repos.JobOfferRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.zaina")
public class JobMicroserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobMicroserviceApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(JobOfferRepo jobOfferRepo) {
        return args -> {
            jobOfferRepo.save(JobOffer.builder()
                    .title("Software Engineer Intern")
                    .description("Software Engineer Intern bla bla bla")
                    .location("Remote")
                    .minSalary(1000)
                    .maxSalary(3000)
                    .employmentType(EmploymentType.FULL_TIME)
                    .jobStatus(JobStatus.PUBLISHED)
                    .build());
        };
    }
}
