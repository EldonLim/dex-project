package com.dex.backend.api.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final StatusQueryService statusQueryService;

    public StatusController(StatusQueryService statusQueryService) {
        this.statusQueryService = statusQueryService;
    }

    @GetMapping
    public BackendStatusResponse getStatus() {
        return statusQueryService.getStatus();
    }
}
