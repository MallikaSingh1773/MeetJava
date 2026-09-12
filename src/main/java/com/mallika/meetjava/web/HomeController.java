package com.mallika.meetjava.web;

import com.mallika.meetjava.model.Meeting;
import com.mallika.meetjava.service.MeetingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Controller
public class HomeController {

    private final MeetingService meetings;

    public HomeController(MeetingService meetings) {
        this.meetings = meetings;
    }

    @GetMapping("/")
    public String home(Model model, @RequestParam(required = false) String error) {
        model.addAttribute("error", error);
        return "index";
    }

    @PostMapping("/meetings")
    public String create(@RequestParam(required = false) String title,
                         @RequestParam(required = false) String name) {
        Meeting meeting = meetings.createMeeting(title, name);
        String who = (name == null || name.isBlank()) ? "Host" : name.trim();
        return "redirect:/meeting/" + meeting.getCode() + "?name=" + java.net.URLEncoder.encode(
                who, java.nio.charset.StandardCharsets.UTF_8);
    }

    @PostMapping("/join")
    public String join(@RequestParam String code, @RequestParam(required = false) String name) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        if (meetings.find(normalized).isEmpty()) {
            return "redirect:/?error=" + java.net.URLEncoder.encode(
                    "No meeting found with code " + normalized,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        String who = (name == null || name.isBlank()) ? "Guest" : name.trim();
        return "redirect:/meeting/" + normalized + "?name=" + java.net.URLEncoder.encode(
                who, java.nio.charset.StandardCharsets.UTF_8);
    }

    @GetMapping("/meeting/{code}")
    public String meeting(@PathVariable String code,
                          @RequestParam(required = false) String name,
                          Model model) {
        Optional<Meeting> meeting = meetings.find(code);
        if (meeting.isEmpty()) {
            return "redirect:/?error=" + java.net.URLEncoder.encode(
                    "No meeting found with code " + code,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        model.addAttribute("meeting", meeting.get());
        model.addAttribute("displayName", (name == null || name.isBlank()) ? "Guest" : name.trim());
        return "meeting";
    }
}
