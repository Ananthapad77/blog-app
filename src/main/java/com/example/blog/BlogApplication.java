package com.example.blog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }

    // Adds one sample post so the home page is not empty on first start
    @Bean
    CommandLineRunner seed(PostRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(new Post("Welcome to my blog",
                        "Admin",
                        "This post was deployed by a Jenkins pipeline to AWS EKS. "
                        + "Use the form on the home page to write your own posts."));
            }
        };
    }
}
