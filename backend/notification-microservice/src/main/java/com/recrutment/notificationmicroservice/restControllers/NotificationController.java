package com.recrutment.notificationmicroservice.restControllers;

import com.recrutment.notificationmicroservice.entity.Notification;
import com.recrutment.notificationmicroservice.repos.NotificationRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepo repo;

    public static final String ACTOR_HEADER = "X-Actor-User-Id";

    @GetMapping("/me")
    public Page<Notification> myNotifications(
            @RequestHeader(ACTOR_HEADER) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @PatchMapping("/{id}/read")
    public void markRead(@PathVariable UUID id, @RequestHeader(ACTOR_HEADER) String userId) {
        Notification n = repo.findById(id).orElseThrow();
        if (!n.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        n.setRead(true);
        repo.save(n);
    }

    @PatchMapping("/me/read-all")
    public void markAllRead(@RequestHeader(ACTOR_HEADER) String userId) {
        List<Notification> list = repo
                .findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged())
                .getContent();
        list.forEach(n -> n.setRead(true));
        repo.saveAll(list);
    }
}