package io.github.panteliszara.issunexa.shared.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("public")
public class PublicSpaController {

    @GetMapping({"/", "/login", "/app", "/app/tickets", "/app/tickets/new", "/app/tickets/{id:[0-9]+}"})
    public String application() {
        return "forward:/index.html";
    }
}
