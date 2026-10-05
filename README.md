# Simple Blog App (Spring Boot + Thymeleaf)

Create, read and delete blog posts. Uses an in-memory H2 database, so posts reset when the pod restarts.
That is fine for this CI/CD demo; swap in MySQL/Postgres for real use.

## Run locally (needs Java 17 + Maven)
    mvn spring-boot:run
Open http://localhost:8080

## Use with the Jenkins pipeline
1. Create a new GitHub repo and push these files to its root.
2. In the pipeline, set the Git URL to that repo and the image name to YOUR_DOCKERHUB_USER/YOUR_REPO.
3. In deployment-service.yml set the same image name.
4. Build. Then run: kubectl get svc -n webapps  and open the EXTERNAL-IP in a browser.

The jar name is blog-1.0.0.jar (artifactId + version in pom.xml). If you change either, update the Dockerfile.
