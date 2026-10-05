package com.example.blog;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class BlogController {

    private final PostRepository repo;

    public BlogController(PostRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("posts", repo.findAllByOrderByCreatedAtDesc());
        return "index";
    }

    @GetMapping("/posts/{id}")
    public String view(@PathVariable Long id, Model model) {
        return repo.findById(id).map(p -> {
            model.addAttribute("post", p);
            return "post";
        }).orElse("redirect:/");
    }

    @PostMapping("/posts")
    public String create(@RequestParam String title,
                         @RequestParam String author,
                         @RequestParam String content) {
        if (!title.isBlank() && !content.isBlank()) {
            repo.save(new Post(title.trim(),
                    author.isBlank() ? "Anonymous" : author.trim(),
                    content.trim()));
        }
        return "redirect:/";
    }

    @PostMapping("/posts/{id}/delete")
    public String delete(@PathVariable Long id) {
        repo.deleteById(id);
        return "redirect:/";
    }
}
