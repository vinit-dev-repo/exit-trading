package com.exittrading.app.controller;

import com.exittrading.app.service.EsmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/esm")
public class EsmController {

    private static final Logger log = LoggerFactory.getLogger(EsmController.class);

    private final EsmService esmService;

    public EsmController(EsmService esmService) {
        this.esmService = esmService;
    }

    @PostMapping("/process")
    public ResponseEntity<com.exittrading.app.dto.EsmResult> processFiles(
            @RequestParam(value = "bseFile", required = false) MultipartFile bseFile,
            @RequestParam(value = "nseFile", required = false) MultipartFile nseFile,
            @RequestParam(value = "reportFile", required = false) MultipartFile reportFile) {
        
        log.info("Received ESM process request. Report: {}", reportFile != null);
        try {
            com.exittrading.app.dto.EsmResult result = esmService.processEsmFiles(bseFile, nseFile, reportFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("ESM processing failed", e);
            return ResponseEntity.internalServerError().body(new com.exittrading.app.dto.EsmResult(null, null, "Error: " + e.getMessage()));
        }
    }
    
    @org.springframework.web.bind.annotation.GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("file") String fileName) {
        Resource r = esmService.getDownloadResource(fileName);
        if (r == null) return ResponseEntity.notFound().build();
         return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.getFilename() + "\"")
                    .body(r);
    }
    
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody ConfirmRequest request) {
        if (request.fileName() == null) return ResponseEntity.badRequest().build();
        esmService.confirmIngestion(request.fileName(), request.corrections() != null ? request.corrections() : java.util.Collections.emptyMap());
        return ResponseEntity.ok().build();
    }
    
    public record ConfirmRequest(String fileName, java.util.Map<String, String> corrections) {}
    
    @org.springframework.web.bind.annotation.GetMapping("/status")
    public org.springframework.http.ResponseEntity<EsmService.EsmStatus> getStatus() {
        return org.springframework.http.ResponseEntity.ok(esmService.getStatus());
    }
}
