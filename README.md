🚀 CI/CD Project: Production-Level Blog App Deployment on AWS EKS
End-to-end DevSecOps pipeline that builds, scans, packages, publishes and deploys a Java (Spring Boot / Maven) Blog Application to Amazon EKS, with code quality gates, vulnerability scanning, an artifact repository and full monitoring.
---
📑 Table of Contents
Tools Used
Architecture & Pipeline Flow
Prerequisites
Project Structure
Step 1 – Infrastructure Setup
Step 2 – Install Jenkins
Step 3 – Install Docker & Trivy
Step 4 – SonarQube
Step 5 – Nexus Repository
Step 6 – Create the EKS Cluster
Step 7 – Kubernetes RBAC for Jenkins
Step 8 – Configure Jenkins
Step 9 – Dockerfile & Kubernetes Manifests
Step 10 – Jenkinsfile (Pipeline)
Step 11 – Monitoring
Step 12 – Verify the Deployment
Production Hardening
Troubleshooting
Cleanup
---
🧰 Tools Used
Category	Tool	Purpose
Cloud	AWS (EC2, EKS, VPC, IAM, ELB)	Hosting CI/CD servers and Kubernetes cluster
Source Control	GitHub	Application source + Jenkinsfile
CI/CD	Jenkins	Pipeline orchestration
Build	Maven + JDK 17	Compile, test, package the app
Code Quality	SonarQube	Static analysis (bugs, smells, coverage) + Quality Gate
Artifact Repo	Nexus Repository (OSS)	Stores Maven artifacts (releases/snapshots)
Security	Trivy	Filesystem scan (source/deps) and Docker image scan
Containerization	Docker	Build and push application image
Registry	Docker Hub (or AWS ECR)	Image storage
Orchestration	Kubernetes (Amazon EKS)	Run the app in production
CLI Tools	kubectl, eksctl, AWS CLI	Cluster provisioning and management
IaC (optional)	Terraform	Provision EC2/EKS infrastructure
Monitoring	Prometheus	Metrics collection
Monitoring	Grafana	Dashboards and visualization
Monitoring	Blackbox Exporter	Website/endpoint uptime probing
Monitoring	Node Exporter	Host-level metrics
Notifications	Email (SMTP / Gmail)	Build status alerts
---
🏗 Architecture & Pipeline Flow
```
Developer ──push──▶ GitHub ──webhook──▶ Jenkins
                                          │
   ┌──────────────────────────────────────┤
   ▼                                      ▼
 Compile & Test (Maven)          Trivy FS Scan
   ▼                                      ▼
 SonarQube Analysis ──▶ Quality Gate ──▶ Build (Maven package)
   ▼
 Publish artifact ──▶ Nexus
   ▼
 Docker Build & Tag ──▶ Trivy Image Scan ──▶ Push to Docker Hub
   ▼
 Deploy to EKS (kubectl apply) ──▶ Verify ──▶ Email notification

 Prometheus + Grafana + Blackbox Exporter monitor Jenkins, nodes and the app URL
```
Servers
Server	Instance Type	Ports	Runs
Jenkins	t2.large (25 GB)	8080	Jenkins, Docker, Trivy, kubectl, Maven
SonarQube	t2.medium (20 GB)	9000	SonarQube (Docker)
Nexus	t2.medium (20 GB)	8081	Nexus (Docker)
Monitoring	t2.medium (20 GB)	9090, 3000, 9115	Prometheus, Grafana, Blackbox
EKS	2–3 × t3.medium nodes	80/443 via LB	Blog app pods
---
✅ Prerequisites
AWS account with an IAM user/role allowed to create EC2, EKS, VPC, IAM, ELB
AWS CLI configured: `aws configure`
A GitHub repository containing the app
Docker Hub account (+ access token)
A Gmail app password (for email notifications)
Key pair for SSH access to EC2
Security group ports to open (restrict source IPs in production):
`22, 25, 80, 443, 465, 8080, 8081, 9000, 9090, 9115, 3000, 30000-32767`
---
📁 Project Structure
```
.
├── Jenkinsfile
├── Dockerfile
├── pom.xml
├── src/
├── k8s/
│   ├── deployment-service.yaml
│   └── rbac/
│       ├── namespace.yaml
│       ├── service-account.yaml
│       ├── role.yaml
│       ├── role-binding.yaml
│       └── secret.yaml
├── monitoring/
│   ├── prometheus.yml
│   └── blackbox.yml
└── README.md
```
---
Step 1 – Infrastructure Setup
Launch 4 Ubuntu 22.04 EC2 instances (Jenkins, SonarQube, Nexus, Monitoring) from the console or Terraform, attach the security group above, and SSH in:
```bash
ssh -i <key>.pem ubuntu@<public-ip>
sudo apt update && sudo apt upgrade -y
```
---
Step 2 – Install Jenkins
```bash
sudo apt install -y fontconfig openjdk-17-jre
sudo wget -O /usr/share/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/" | \
  sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update && sudo apt install -y jenkins
sudo systemctl enable --now jenkins
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```
Open `http://<jenkins-ip>:8080`, paste the password, install suggested plugins, create the admin user.
---
Step 3 – Install Docker & Trivy
Docker (on Jenkins, SonarQube, Nexus servers):
```bash
sudo apt install -y docker.io
sudo usermod -aG docker $USER
sudo usermod -aG docker jenkins      # Jenkins server only
newgrp docker
sudo systemctl restart jenkins       # Jenkins server only
```
Trivy (Jenkins server):
```bash
sudo apt install -y wget apt-transport-https gnupg lsb-release
wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | \
  gpg --dearmor | sudo tee /usr/share/keyrings/trivy.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] \
  https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | \
  sudo tee /etc/apt/sources.list.d/trivy.list
sudo apt update && sudo apt install -y trivy
trivy --version
```
kubectl, AWS CLI, eksctl (Jenkins server):
```bash
sudo snap install kubectl --classic
sudo snap install aws-cli --classic
curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_Linux_amd64.tar.gz"
tar -xzf eksctl_Linux_amd64.tar.gz && sudo mv eksctl /usr/local/bin
```
---
Step 4 – SonarQube
On the SonarQube server:
```bash
docker run -d --name sonar -p 9000:9000 sonarqube:lts-community
```
Open `http://<sonar-ip>:9000` (default `admin` / `admin`, change it).
Administration → Security → Users → Tokens → generate a token (save it).
Administration → Configuration → Webhooks → Create:
`http://<jenkins-ip>:8080/sonarqube-webhook/` (required for the Quality Gate stage).
---
Step 5 – Nexus Repository
```bash
docker run -d --name nexus -p 8081:8081 sonatype/nexus3
docker exec -it nexus cat /nexus-data/admin.password
```
Open `http://<nexus-ip>:8081`, sign in as `admin`, finish the setup wizard.
Note the `maven-releases` and `maven-snapshots` repositories.
Add to the project `pom.xml`:
```xml
<distributionManagement>
  <repository>
    <id>maven-releases</id>
    <url>http://<nexus-ip>:8081/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>maven-snapshots</id>
    <url>http://<nexus-ip>:8081/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```
> Use `-SNAPSHOT` in `<version>` to publish to snapshots; otherwise releases.
---
Step 6 – Create the EKS Cluster
```bash
aws configure    # or attach an IAM role to the Jenkins EC2

eksctl create cluster \
  --name blog-eks \
  --region ap-south-1 \
  --version 1.30 \
  --nodegroup-name blog-nodes \
  --node-type t3.medium \
  --nodes 2 --nodes-min 2 --nodes-max 4 \
  --managed

aws eks update-kubeconfig --name blog-eks --region ap-south-1
kubectl get nodes
```
(Cluster creation takes ~15–20 minutes. Adjust the version to a currently supported EKS release.)
---
Step 7 – Kubernetes RBAC for Jenkins
Create a namespace and a service account that Jenkins uses to deploy.
`k8s/rbac/namespace.yaml`
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: webapps
```
`k8s/rbac/service-account.yaml`
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins
  namespace: webapps
```
`k8s/rbac/role.yaml`
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-role
  namespace: webapps
rules:
  - apiGroups: ["", "apps", "autoscaling", "batch", "extensions", "policy", "rbac.authorization.k8s.io"]
    resources: ["pods","secrets","configmaps","services","deployments","replicasets",
                "statefulsets","daemonsets","jobs","cronjobs","persistentvolumeclaims",
                "horizontalpodautoscalers","serviceaccounts","events"]
    verbs: ["get","list","watch","create","update","patch","delete"]
```
`k8s/rbac/role-binding.yaml`
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-rolebinding
  namespace: webapps
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: app-role
subjects:
  - kind: ServiceAccount
    name: jenkins
    namespace: webapps
```
`k8s/rbac/secret.yaml` (long-lived token for the service account)
```yaml
apiVersion: v1
kind: Secret
type: kubernetes.io/service-account-token
metadata:
  name: mysecretname
  namespace: webapps
  annotations:
    kubernetes.io/service-account.name: jenkins
```
Apply and fetch the token:
```bash
kubectl apply -f k8s/rbac/namespace.yaml
kubectl apply -f k8s/rbac/
kubectl describe secret mysecretname -n webapps    # copy the token
```
---
Step 8 – Configure Jenkins
8.1 Plugins
Manage Jenkins → Plugins → Available:
Eclipse Temurin Installer
Config File Provider
Pipeline Maven Integration
SonarQube Scanner
Docker, Docker Pipeline
Kubernetes, Kubernetes CLI, Kubernetes Credentials, Kubernetes Client API
Pipeline: Stage View
Email Extension Template
Prometheus metrics
8.2 Tools (Manage Jenkins → Tools)
Tool	Name	Setting
JDK	`jdk17`	Install from adoptium.net (jdk-17)
Maven	`maven3`	Install automatically (3.9.x)
SonarQube Scanner	`sonar-scanner`	Install automatically
Docker	`docker`	Install automatically (latest)
8.3 Credentials (Manage Jenkins → Credentials → Global)
ID	Type	Value
`git-cred`	Username + password	GitHub user + PAT
`sonar-token`	Secret text	SonarQube token
`docker-cred`	Username + password	Docker Hub user + token
`k8s-cred`	Secret text	ServiceAccount token from Step 7
`mail-cred`	Username + password	Gmail address + app password
8.4 SonarQube server (Manage Jenkins → System)
Name: `sonar`
URL: `http://<sonar-ip>:9000`
Token: `sonar-token`
8.5 Maven settings for Nexus (Managed files → Add → Maven settings.xml)
ID: `maven-settings`
```xml
<servers>
  <server>
    <id>maven-releases</id>
    <username>admin</username>
    <password>NEXUS_PASSWORD</password>
  </server>
  <server>
    <id>maven-snapshots</id>
    <username>admin</username>
    <password>NEXUS_PASSWORD</password>
  </server>
</servers>
```
8.6 Email (Manage Jenkins → System)
SMTP server: `smtp.gmail.com`, port `465`, SSL enabled
Credentials: `mail-cred`
Default recipients / Jenkins admin email configured
8.7 GitHub webhook
GitHub repo → Settings → Webhooks → `http://<jenkins-ip>:8080/github-webhook/` (content type `application/json`), then enable GitHub hook trigger in the job.
---
Step 9 – Dockerfile & Kubernetes Manifests
`Dockerfile`
```dockerfile
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
```
`k8s/deployment-service.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: blogapp
  namespace: webapps
spec:
  replicas: 2
  selector:
    matchLabels:
      app: blogapp
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  template:
    metadata:
      labels:
        app: blogapp
    spec:
      containers:
        - name: blogapp
          image: <dockerhub-user>/blogapp:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          resources:
            requests: { cpu: "250m", memory: "256Mi" }
            limits:   { cpu: "500m", memory: "512Mi" }
          readinessProbe:
            httpGet: { path: /, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /, port: 8080 }
            initialDelaySeconds: 40
            periodSeconds: 15
---
apiVersion: v1
kind: Service
metadata:
  name: blogapp-svc
  namespace: webapps
spec:
  type: LoadBalancer
  selector:
    app: blogapp
  ports:
    - port: 80
      targetPort: 8080
```
---
Step 10 – Jenkinsfile (Pipeline)
```groovy
pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        SCANNER_HOME = tool 'sonar-scanner'
        IMAGE_NAME   = "<dockerhub-user>/blogapp"
        IMAGE_TAG    = "${BUILD_NUMBER}"
    }

    stages {
        stage('Git Checkout') {
            steps {
                git branch: 'main', credentialsId: 'git-cred',
                    url: 'https://github.com/<your-user>/<your-repo>.git'
            }
        }

        stage('Compile') {
            steps { sh 'mvn compile' }
        }

        stage('Test') {
            steps { sh 'mvn test -DskipTests=true' }
        }

        stage('Trivy FS Scan') {
            steps {
                sh 'trivy fs --format table -o fs-report.html .'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('sonar') {
                    sh '''$SCANNER_HOME/bin/sonar-scanner \
                      -Dsonar.projectName=Blogging-App \
                      -Dsonar.projectKey=Blogging-App \
                      -Dsonar.java.binaries=target'''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: false,
                                       credentialsId: 'sonar-token'
                }
            }
        }

        stage('Build') {
            steps { sh 'mvn package -DskipTests=true' }
        }

        stage('Publish to Nexus') {
            steps {
                withMaven(globalMavenSettingsConfig: 'maven-settings',
                          maven: 'maven3', traceability: true) {
                    sh 'mvn deploy -DskipTests=true'
                }
            }
        }

        stage('Docker Build & Tag') {
            steps {
                script {
                    withDockerRegistry(credentialsId: 'docker-cred') {
                        sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                        sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
                    }
                }
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh "trivy image --severity HIGH,CRITICAL --format table -o image-report.html ${IMAGE_NAME}:${IMAGE_TAG}"
            }
        }

        stage('Docker Push') {
            steps {
                script {
                    withDockerRegistry(credentialsId: 'docker-cred') {
                        sh "docker push ${IMAGE_NAME}:${IMAGE_TAG}"
                        sh "docker push ${IMAGE_NAME}:latest"
                    }
                }
            }
        }

        stage('Deploy to EKS') {
            steps {
                withKubeConfig(caCertificate: '', clusterName: 'blog-eks',
                               contextName: '', credentialsId: 'k8s-cred',
                               namespace: 'webapps',
                               restrictKubeConfigAccess: false,
                               serverUrl: '<EKS-API-SERVER-ENDPOINT>') {
                    sh "sed -i 's|${IMAGE_NAME}:.*|${IMAGE_NAME}:${IMAGE_TAG}|' k8s/deployment-service.yaml"
                    sh 'kubectl apply -f k8s/deployment-service.yaml'
                }
            }
        }

        stage('Verify Deployment') {
            steps {
                withKubeConfig(caCertificate: '', clusterName: 'blog-eks',
                               contextName: '', credentialsId: 'k8s-cred',
                               namespace: 'webapps',
                               restrictKubeConfigAccess: false,
                               serverUrl: '<EKS-API-SERVER-ENDPOINT>') {
                    sh 'kubectl rollout status deployment/blogapp -n webapps --timeout=120s'
                    sh 'kubectl get pods -n webapps'
                    sh 'kubectl get svc -n webapps'
                }
            }
        }
    }

    post {
        always {
            script {
                def status = currentBuild.result ?: 'SUCCESS'
                emailext(
                    subject: "${JOB_NAME} - Build #${BUILD_NUMBER} - ${status}",
                    body: """<p>Pipeline: ${JOB_NAME}</p>
                             <p>Build: #${BUILD_NUMBER}</p>
                             <p>Status: <b>${status}</b></p>
                             <p>URL: <a href='${BUILD_URL}'>${BUILD_URL}</a></p>""",
                    to: 'you@example.com',
                    mimeType: 'text/html',
                    attachmentsPattern: 'fs-report.html,image-report.html'
                )
            }
        }
    }
}
```
> Get the EKS API endpoint with:
> `aws eks describe-cluster --name blog-eks --query cluster.endpoint --output text`
---
Step 11 – Monitoring (Prometheus, Grafana, Blackbox)
On the Monitoring server.
11.1 Prometheus
```bash
wget https://github.com/prometheus/prometheus/releases/download/v2.53.0/prometheus-2.53.0.linux-amd64.tar.gz
tar -xvf prometheus-2.53.0.linux-amd64.tar.gz
sudo mv prometheus-2.53.0.linux-amd64 /opt/prometheus
```
11.2 Blackbox Exporter
```bash
wget https://github.com/prometheus/blackbox_exporter/releases/download/v0.25.0/blackbox_exporter-0.25.0.linux-amd64.tar.gz
tar -xvf blackbox_exporter-0.25.0.linux-amd64.tar.gz
sudo mv blackbox_exporter-0.25.0.linux-amd64 /opt/blackbox
cd /opt/blackbox && nohup ./blackbox_exporter &
```
11.3 `prometheus.yml`
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']

  - job_name: blackbox
    metrics_path: /probe
    params:
      module: [http_2xx]
    static_configs:
      - targets:
          - http://<EKS-LOADBALANCER-DNS>      # Blog app
          - http://<jenkins-ip>:8080            # Jenkins
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: <monitoring-ip>:9115

  - job_name: jenkins
    metrics_path: /prometheus
    static_configs:
      - targets: ['<jenkins-ip>:8080']

  - job_name: node
    static_configs:
      - targets: ['<jenkins-ip>:9100', '<sonar-ip>:9100', '<nexus-ip>:9100']
```
Start Prometheus:
```bash
cd /opt/prometheus && nohup ./prometheus --config.file=prometheus.yml &
```
Open `http://<monitoring-ip>:9090/targets` and confirm all targets are UP.
11.4 Node Exporter (on every server you want to monitor)
```bash
wget https://github.com/prometheus/node_exporter/releases/download/v1.8.1/node_exporter-1.8.1.linux-amd64.tar.gz
tar -xvf node_exporter-1.8.1.linux-amd64.tar.gz
cd node_exporter-1.8.1.linux-amd64 && nohup ./node_exporter &
```
11.5 Grafana
```bash
sudo apt install -y apt-transport-https software-properties-common
sudo mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt update && sudo apt install -y grafana
sudo systemctl enable --now grafana-server
```
Open `http://<monitoring-ip>:3000` (default `admin` / `admin`).
Connections → Data sources → Add → Prometheus → URL `http://localhost:9090`.
Dashboards → Import:
`7587` – Blackbox Exporter (HTTP probe)
`1860` – Node Exporter Full
`9964` – Jenkins performance & health
11.6 Suggested alerts
Blog URL probe fails > 2 min (`probe_success == 0`)
Node CPU > 80% for 5 min
Disk usage > 85%
Jenkins job failure rate spike
---
Step 12 – Verify the Deployment
```bash
kubectl get pods -n webapps
kubectl get svc  -n webapps       # copy EXTERNAL-IP / DNS of blogapp-svc
```
Visit `http://<EXTERNAL-LB-DNS>` to see the Blog App.
Checklist:
[ ] Pipeline is green in Jenkins
[ ] SonarQube project shows Quality Gate Passed
[ ] Artifact `.jar` is in Nexus
[ ] Trivy reports attached to the email
[ ] Image present on Docker Hub
[ ] Pods `Running` on EKS
[ ] Blackbox dashboard shows probe UP
---
🔒 Production Hardening Recommendations
Put the app behind an Ingress (AWS Load Balancer Controller / NGINX) with TLS (ACM / cert-manager) and a Route 53 domain.
Use private subnets for nodes; restrict Jenkins/Sonar/Nexus access with VPN or IP allow-lists instead of open SGs.
Replace long-lived credentials with IAM Roles (IRSA) and an instance profile for Jenkins.
Use Amazon ECR instead of Docker Hub, and enable ECR image scanning.
Fail the pipeline on HIGH/CRITICAL findings (`trivy --exit-code 1`) and set `abortPipeline: true` on the Quality Gate.
Add HPA and PodDisruptionBudget; keep replicas ≥ 2 across AZs.
Store secrets in AWS Secrets Manager / External Secrets, not in YAML.
Use Jenkins agents (Kubernetes pods) rather than building on the controller.
Back up Jenkins home, Nexus data, and SonarQube DB (use PostgreSQL + EBS volumes).
Run Prometheus/Grafana as services (systemd or Helm `kube-prometheus-stack`) with persistent storage and Alertmanager.
Add blue/green or canary releases (Argo Rollouts) and a GitOps tool (ArgoCD) for further maturity.
---
🛠 Troubleshooting
Problem	Fix
`docker: permission denied` in Jenkins	`sudo usermod -aG docker jenkins && sudo systemctl restart jenkins`
Quality Gate hangs	Verify the SonarQube webhook URL ends with `/sonarqube-webhook/`
Nexus `401 Unauthorized` on deploy	Server `<id>` in `settings.xml` must match `pom.xml` repo IDs
Nexus `400 Repository does not allow updating assets`	Bump version, or use `-SNAPSHOT`
`kubectl` forbidden	Check Role/RoleBinding and that the token belongs to the `jenkins` ServiceAccount
SonarQube container exits	`sudo sysctl -w vm.max_map_count=262144`
Pods `ImagePullBackOff`	Check image name/tag and Docker Hub visibility or `imagePullSecrets`
LoadBalancer stuck `<pending>`	Ensure subnet tags and IAM permissions for ELB creation
Prometheus target DOWN	Check security group ports (9100, 9115, 8080) and exporter processes
---
🧹 Cleanup
```bash
kubectl delete -f k8s/deployment-service.yaml
eksctl delete cluster --name blog-eks --region ap-south-1
# Terminate EC2 instances, release Elastic IPs, delete unused EBS volumes and the security group
```
---
🙌 Acknowledgements
Inspired by the community DevSecOps blog-app project. Feel free to fork, improve and open a PR.
Author: Your Name · LinkedIn · GitHub
