package com.example.ping;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ping")
public class PingController {

    @GetMapping
    public String get() {
        return "pong - GET";
    }

    @PostMapping
    public String post() {
        return "pong - POST";
    }

    @PutMapping
    public String put() {
        return "pong - PUT";
    }

    @DeleteMapping
    public String delete() {
        return "pong - DELETE";
    }
}
