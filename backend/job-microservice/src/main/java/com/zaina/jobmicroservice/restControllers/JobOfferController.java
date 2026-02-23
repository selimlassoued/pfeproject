package com.zaina.jobmicroservice.restControllers;

import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.services.JobOfferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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

    public static final String ACTOR_USER_ID_HEADER = "X-Actor-User-Id";

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobOfferDto create(
            @RequestBody JobOfferDto dto,
            @RequestHeader(name = ACTOR_USER_ID_HEADER, required = false) String actorUserId) {
        return service.createJobOffer(dto, actorUserId);
    }

    @PutMapping("/{id}")
    public JobOfferDto update(
            @PathVariable UUID id,
            @RequestBody JobOfferDto dto,
            @RequestParam(required = false) String reason,
            @RequestHeader(name = ACTOR_USER_ID_HEADER, required = false) String actorUserId) {
        return service.updateJobOffer(id, dto, reason, actorUserId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteJobOffer(id);
    }
}
