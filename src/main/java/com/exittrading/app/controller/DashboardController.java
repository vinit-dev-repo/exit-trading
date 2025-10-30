package com.exittrading.app.controller;

import com.exittrading.app.service.AdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final AdminService adminService;

    public DashboardController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("users", adminService.allUsers());
        return "dashboard";
    }
}
