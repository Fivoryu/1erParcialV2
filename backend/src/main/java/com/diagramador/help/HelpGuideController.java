package com.diagramador.help;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/help", produces = MediaType.APPLICATION_JSON_VALUE)
public class HelpGuideController {

    private final HelpGuideService helpGuideService;

    public HelpGuideController(HelpGuideService helpGuideService) {
        this.helpGuideService = helpGuideService;
    }

    @PostMapping(path = "/guide", consumes = MediaType.APPLICATION_JSON_VALUE)
    public HelpGuideResponse guide(@Valid @RequestBody HelpGuideRequest request) {
        return helpGuideService.answer(request);
    }
}
