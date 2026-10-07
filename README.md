# CICD PROJECT: Production Level Blog APP Deployment using EKS, Nexus, SonarQube, Trivy with Monitoring Tools

![architecture](https://miro.medium.com/v2/resize:fit:700/1*9CvhrnA6Fg1LTmMjr3n3Kg.gif)

A complete DevSecOps pipeline that builds, tests, scans, packages and deploys a Java (Spring Boot) blog application to **Amazon EKS**, provisioned with **Terraform** and monitored with **Prometheus, Blackbox Exporter and Grafana**.

## 🧰 Tools Used

| Tool | Purpose |
|---|---|
| **Git / GitHub** | Source control and webhook trigger |
| **Jenkins** | CI/CD orchestration |
| **JDK 17 + Maven** | Compile, unit test, package, publish |
| **Trivy** | Filesystem scan and Docker image scan |
| **SonarQube** | Static code analysis and Quality Gate |
| **Nexus Repository** | Maven artifact storage |
| **Docker / DockerHub (private repo)** | Image build and storage |
| **Terraform** | Provision VPC and EKS cluster |
| **Kubernetes (AWS EKS)** | Runs the application |
| **AWS Load Balancer + Route 53** | Public access and domain mapping |
| **Prometheus + Blackbox Exporter** | Metrics and uptime probing |
| **Grafana** | Dashboards |

## 🔄 Pipeline Flow

```
Developer → Git → Jenkins → Compile → Unit Test → Trivy FS Scan → SonarQube
→ Maven Build → Nexus → Docker Build → Trivy Image Scan → DockerHub (private)
→ Kubernetes (EKS) → Load Balancer → Domain (Route 53) → Blogging App
                         Prometheus + Blackbox + Grafana monitor everything
```

## 📁 Project Structure

```
.
├── Jenkinsfile
├── Dockerfile
├── pom.xml
├── sonar-project.properties
├── src/
├── terraform/
│   ├── main.tf
│   ├── variables.tf
│   └── outputs.tf
├── k8s/
│   ├── deployment-service.yaml
│   └── rbac/
└── monitoring/
    ├── prometheus.yml
    ├── blackbox.yml
    └── alert-rules.yml
```

---

## 1. Provision EKS with Terraform

**`terraform/variables.tf`**
```hcl
variable "region"       { default = "ap-south-1" }
variable "cluster_name" { default = "blog-eks" }
```

**`terraform/main.tf`**
```hcl
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
}

data "aws_availability_zones" "available" {}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "${var.cluster_name}-vpc"
  cidr = "10.0.0.0/16"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)

  private_subnets = ["10.0.1.0/24", "10.0.2.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24"]

  enable_nat_gateway   = true
  single_nat_gateway   = true
  enable_dns_hostnames = true

  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = var.cluster_name
  cluster_version = "1.30"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access           = true
  enable_cluster_creator_admin_permissions = true

  eks_managed_node_groups = {
    blog_nodes = {
      instance_types = ["t3.medium"]
      min_size       = 2
      max_size       = 4
      desired_size   = 2
    }
  }
}
```

**`terraform/outputs.tf`**
```hcl
output "cluster_name"     { value = module.eks.cluster_name }
output "cluster_endpoint" { value = module.eks.cluster_endpoint }
```

Run it:
```bash
cd terraform
terraform init
terraform plan
terraform apply -auto-approve
aws eks update-kubeconfig --name blog-eks --region ap-south-1
kubectl get nodes
```

---

## 2. Jenkins, Docker, Trivy, kubectl (Jenkins server)

```bash
sudo apt update && sudo apt install -y fontconfig openjdk-17-jre docker.io
sudo wget -O /usr/share/keyrings/jenkins-keyring.asc https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update && sudo apt install -y jenkins
sudo usermod -aG docker jenkins && sudo systemctl restart jenkins

# Trivy
wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | gpg --dearmor | sudo tee /usr/share/keyrings/trivy.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list
sudo apt update && sudo apt install -y trivy

# kubectl + AWS CLI
sudo snap install kubectl --classic
sudo snap install aws-cli --classic
```

## 3. SonarQube and Nexus (Docker)

```bash
# SonarQube server
docker run -d --name sonar -p 9000:9000 sonarqube:lts-community

# Nexus server
docker run -d --name nexus -p 8081:8081 sonatype/nexus3
docker exec -it nexus cat /nexus-data/admin.password
```
SonarQube webhook: `http://<jenkins-ip>:8080/sonarqube-webhook/`

---

## 4. Kubernetes RBAC for Jenkins

```bash
kubectl apply -f k8s/rbac/namespace.yaml
kubectl apply -f k8s/rbac/
kubectl describe secret mysecretname -n webapps    # copy token → Jenkins credential "k8s-cred"
```

---

## 5. DockerHub Private Repo (imagePullSecret)

Create the pull secret in the `webapps` namespace:
```bash
kubectl create secret docker-registry regcred \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=<dockerhub-user> \
  --docker-password=<dockerhub-access-token> \
  --docker-email=<email> \
  -n webapps
```

Reference it in `k8s/deployment-service.yaml`:
```yaml
    spec:
      imagePullSecrets:
        - name: regcred
      containers:
        - name: blogapp
          image: <dockerhub-user>/blogapp:latest
```

---

## 6. Domain Mapping (Route 53 + HTTPS)

Request an ACM certificate for your domain (e.g. `blog.example.com`) in the same region, then annotate the Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: blogapp-svc
  namespace: webapps
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-ssl-cert: arn:aws:acm:ap-south-1:<account-id>:certificate/<cert-id>
    service.beta.kubernetes.io/aws-load-balancer-ssl-ports: "443"
    service.beta.kubernetes.io/aws-load-balancer-backend-protocol: http
spec:
  type: LoadBalancer
  selector:
    app: blogapp
  ports:
    - name: http
      port: 80
      targetPort: 8080
    - name: https
      port: 443
      targetPort: 8080
```

Get the load balancer hostname:
```bash
kubectl get svc blogapp-svc -n webapps -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Point your domain at it (Route 53 CNAME):
```bash
cat > record.json <<EOF
{
  "Changes": [{
    "Action": "UPSERT",
    "ResourceRecordSet": {
      "Name": "blog.example.com",
      "Type": "CNAME",
      "TTL": 300,
      "ResourceRecords": [{ "Value": "<load-balancer-hostname>" }]
    }
  }]
}
EOF
aws route53 change-resource-record-sets --hosted-zone-id <ZONE_ID> --change-batch file://record.json
```

---

## 7. Jenkins Configuration

**Plugins:** Eclipse Temurin Installer, Config File Provider, Pipeline Maven Integration, SonarQube Scanner, Docker, Docker Pipeline, Kubernetes, Kubernetes CLI, Kubernetes Credentials, Email Extension Template, Prometheus metrics.

**Tools:** `jdk17`, `maven3`, `sonar-scanner`, `docker`

**Credentials:**

| ID | Type |
|---|---|
| `git-cred` | GitHub username + token |
| `sonar-token` | Secret text |
| `docker-cred` | DockerHub username + token |
| `k8s-cred` | Secret text (ServiceAccount token) |
| `mail-cred` | Gmail + app password |

**Managed file:** `maven-settings` (Nexus `maven-releases` and `maven-snapshots` server IDs with credentials)

The full pipeline is in the [`Jenkinsfile`](./Jenkinsfile).

---

## 8. Monitoring (Prometheus, Blackbox, Grafana)

```bash
# Prometheus
wget https://github.com/prometheus/prometheus/releases/download/v2.53.0/prometheus-2.53.0.linux-amd64.tar.gz
tar -xvf prometheus-2.53.0.linux-amd64.tar.gz && sudo mv prometheus-2.53.0.linux-amd64 /opt/prometheus

# Blackbox Exporter
wget https://github.com/prometheus/blackbox_exporter/releases/download/v0.25.0/blackbox_exporter-0.25.0.linux-amd64.tar.gz
tar -xvf blackbox_exporter-0.25.0.linux-amd64.tar.gz && sudo mv blackbox_exporter-0.25.0.linux-amd64 /opt/blackbox

# Start
cd /opt/blackbox   && nohup ./blackbox_exporter &
cd /opt/prometheus && nohup ./prometheus --config.file=prometheus.yml &

# Grafana
sudo apt install -y apt-transport-https software-properties-common
sudo mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt update && sudo apt install -y grafana
sudo systemctl enable --now grafana-server
```

- Prometheus: `http://<monitoring-ip>:9090`
- Grafana: `http://<monitoring-ip>:3000` → add Prometheus data source → import dashboards **7587** (Blackbox), **1860** (Node Exporter), **9964** (Jenkins)

Configs are in the [`monitoring/`](./monitoring) folder.

---

## 9. Verify

```bash
kubectl get pods -n webapps
kubectl get svc  -n webapps
```
Open `https://blog.example.com`.

## 🧹 Cleanup

```bash
kubectl delete -f k8s/deployment-service.yaml
cd terraform && terraform destroy -auto-approve
```

## 📌 Ports

`22, 80, 443, 8080 (Jenkins), 8081 (Nexus), 9000 (SonarQube), 9090 (Prometheus), 9115 (Blackbox), 3000 (Grafana), 9100 (Node Exporter)`

## 👤 Author

**Your Name** · [GitHub](#) · [LinkedIn](#)
