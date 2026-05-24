package de.visterion.dracul;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ConditionalOnResource(resources = "classpath:static/index.html")
public class SpaFallbackController {

    private final Resource indexHtml = new ClassPathResource("static/index.html");

    @GetMapping(value = {
        "/{p:[^\\.]*}",
        "/{p1:[^\\.]*}/{p2:[^\\.]*}"
    })
    @ResponseBody
    public ResponseEntity<Resource> spa() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(indexHtml);
    }
}
