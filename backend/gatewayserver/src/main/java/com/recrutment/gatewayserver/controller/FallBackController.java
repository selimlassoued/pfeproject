package com.recrutment.gatewayserver.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallBackController {
    @RequestMapping("/contactAdmin")
    public ResponseEntity<String> contactAdmin() {
        return new ResponseEntity<>("An error occurred. Please contact the administrator. !",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}