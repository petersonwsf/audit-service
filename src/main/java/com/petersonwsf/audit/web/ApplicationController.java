package com.petersonwsf.audit.web;

import com.petersonwsf.audit.security.Requires;
import com.petersonwsf.audit.security.Role;
import com.petersonwsf.audit.service.ApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Aplicações: lista enxuta para leitura e administração (cadastro, retenção e chaves de escrita). */
@RestController
@RequestMapping("/v1")
public class ApplicationController {

    private final ApplicationService applications;

    public ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping("/applications")
    @Requires(Role.READ)
    public List<Dtos.ApplicationSummary> summaries() {
        return applications.list().stream().map(Dtos.ApplicationSummary::of).toList();
    }

    @GetMapping("/admin/applications")
    @Requires(Role.ADMIN)
    public List<Dtos.ApplicationView> list() {
        return applications.list().stream().map(Dtos.ApplicationView::of).toList();
    }

    @PostMapping("/admin/applications")
    @Requires(Role.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ApplicationView create(@RequestBody Dtos.CreateApplicationRequest request) {
        return Dtos.ApplicationView.of(applications.create(request.code(), request.name(), request.retentionDays()));
    }

    @PatchMapping("/admin/applications/{code}")
    @Requires(Role.ADMIN)
    public Dtos.ApplicationView update(@PathVariable String code, @RequestBody Dtos.UpdateApplicationRequest request) {
        return Dtos.ApplicationView.of(
                applications.update(code, request.name(), request.active(), request.retentionDays()));
    }

    @GetMapping("/admin/applications/{code}/keys")
    @Requires(Role.ADMIN)
    public List<Dtos.KeyView> keys(@PathVariable String code) {
        return applications.listKeys(code).stream().map(Dtos.KeyView::of).toList();
    }

    @PostMapping("/admin/applications/{code}/keys")
    @Requires(Role.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.CreatedKeyView createKey(@PathVariable String code) {
        return Dtos.CreatedKeyView.of(applications.createKey(code));
    }

    @DeleteMapping("/admin/applications/{code}/keys/{keyId}")
    @Requires(Role.ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable String code, @PathVariable UUID keyId) {
        applications.revokeKey(code, keyId);
    }
}
