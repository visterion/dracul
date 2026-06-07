package de.visterion.dracul.vistierie;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vistierie")
public class VistierieController {

    private final VistierieDataService dataService;

    public VistierieController(VistierieDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping
    public VistierieData get() {
        return dataService.getData();
    }
}
