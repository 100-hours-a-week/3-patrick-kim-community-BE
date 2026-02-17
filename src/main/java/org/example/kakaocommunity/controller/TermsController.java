package org.example.kakaocommunity.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

@Controller
public class TermsController {

    //MVC - 타임리프 템플릿을 제공
    @GetMapping("/terms")
    public String terms(Model model, HttpServletResponse response)
    {

        model.addAttribute("pageTitle", "커뮤니티 이용약관");
        model.addAttribute("companyName", "타임리프 커뮤니티");
        model.addAttribute("serviceName", "타임리프 커뮤니티");
        model.addAttribute("lastUpdated", LocalDate.of(2025, 10, 23));
        return "legal/terms";
    }

    @GetMapping("/privacy")
    public String privacy(Model model, HttpServletResponse response){

        model.addAttribute("pageTitle", "개인정보처리방침");
        model.addAttribute("companyName", "타임리프 커뮤니티");
        model.addAttribute("serviceName", "타임리프 커뮤니티");
        model.addAttribute("privacyOfficerName", "홍길동");
        model.addAttribute("contactEmail", "privacy@example.com");
        model.addAttribute("companyAddress", "서울특별시 ○○구 ○○로 00");
        model.addAttribute("lastUpdated", LocalDate.of(2025, 10, 23));
        return "legal/privacy";
    }
}
