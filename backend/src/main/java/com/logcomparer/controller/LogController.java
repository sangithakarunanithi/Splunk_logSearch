package com.logcomparer.controller;

import com.logcomparer.model.ComparisonResult;
import com.logcomparer.service.CsvParserService;
import com.logcomparer.service.LogComparisonService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class LogController {

    private final CsvParserService parserService;
    private final LogComparisonService comparisonService;

    public LogController(CsvParserService parserService, LogComparisonService comparisonService) {
        this.parserService = parserService;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/compare")
    public String compare(@RequestParam("fileA") MultipartFile fileA,
                          @RequestParam("fileB") MultipartFile fileB,
                          Model model) {
        try {
            if (fileA.isEmpty() || fileB.isEmpty()) {
                model.addAttribute("error", "Please upload both CSV files");
                return "index";
            }

            String nameA = fileA.getOriginalFilename();
            String nameB = fileB.getOriginalFilename();

            var logA = parserService.parse(fileA);
            var logB = parserService.parse(fileB);

            ComparisonResult result = comparisonService.compare(logA, logB, nameA, nameB);

            model.addAttribute("result", result);
            return "result";

        } catch (Exception e) {
            model.addAttribute("error", "Error processing files: " + e.getMessage());
            return "index";
        }
    }
}
