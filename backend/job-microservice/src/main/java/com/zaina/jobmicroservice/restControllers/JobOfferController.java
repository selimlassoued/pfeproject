package com.zaina.jobmicroservice.restControllers;

import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.services.JobOfferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobOfferController {

    private final JobOfferService service;

    @GetMapping
    public List<JobOfferDto> getAll() {
        return service.getJobOffers();
    }

    @GetMapping("/{id}")
    public JobOfferDto getById(@PathVariable UUID id) {
        return service.getJobOfferById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobOfferDto create(@RequestBody JobOfferDto dto) {
        return service.createJobOffer(dto);
    }

    @PutMapping("/{id}")
    public JobOfferDto update(@PathVariable UUID id, @RequestBody JobOfferDto dto) {
        return service.updateJobOffer(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteJobOffer(id);
    }
}
