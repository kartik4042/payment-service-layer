# Deployment Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Local Development Setup](#local-development-setup)
3. [Docker Deployment](#docker-deployment)
4. [Production Deployment](#production-deployment)
5. [Configuration](#configuration)
6. [Monitoring & Observability](#monitoring--observability)
7. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Software
- **Java 17+** (OpenJDK or Oracle JDK)
- **Gradle 8.5+** (or use included wrapper)
- **Docker 20.10+** and Docker Compose 2.0+
- **PostgreSQL 14+**
- **Redis 7+**

### Optional Tools
- **kubectl** (for Kubernetes deployment)
- **Helm 3+** (for Kubernetes package management)
- **Terraform** (for infrastructure as code)

---

## Local Development Setup

### 1. Clone Repository
```bash
git clone <repository-url>
cd payment-orchestration
```

### 2. Configure Environment
```bash
# Copy example environment file
cp .env.example .env

# Edit .env with your local settings
vim .env
```

### 3. Start Infrastructure Services
```bash
# Start PostgreSQL, Redis, Zipkin, Prometheus, Grafana
docker-compose up -d postgres redis zipkin prometheus grafana

# Verify services are running
docker-compose ps
```

### 4. Run Database Migrations
```bash
# Flyway will run automatically on application startup
# Or run manually:
./gradlew flywayMigrate
```

### 5. Build Application
```bash
# Build without tests
./gradlew clean build -x test

# Build with tests
./gradlew clean build
```

### 6. Run Application
```bash
# Using Gradle
./gradlew bootRun

# Or using JAR
java -jar build/libs/payment-orchestration-*.jar

# With specific profile
java -jar build/libs/payment-orchestration-*.jar --spring.profiles.active=dev
```

### 7. Verify Application
```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics

# Create test payment
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-123" \
  -d '{
    "amount": 100.00,
    "currency": "USD",
    "paymentMethod": "CREDIT_CARD",
    "customerCountry": "US"
  }'
```

---

## Docker Deployment

### 1. Build Docker Image
```bash
# Build application image
docker build -t payment-orchestration:latest .

# Or with specific tag
docker build -t payment-orchestration:1.0.0 .
```

### 2. Run with Docker Compose
```bash
# Start all services (app + infrastructure)
docker-compose up -d

# View logs
docker-compose logs -f payment-orchestration

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

### 3. Access Services
- **Application**: http://localhost:8080
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)
- **Zipkin**: http://localhost:9411
- **PostgreSQL**: localhost:5432
- **Redis**: localhost:6379

---

## Production Deployment

### Architecture Overview
```
┌─────────────────┐
│  Load Balancer  │
│   (AWS ALB)     │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
┌───▼───┐ ┌──▼────┐
│ App 1 │ │ App 2 │  (ECS/EKS)
└───┬───┘ └──┬────┘
    │        │
    └────┬───┘
         │
    ┌────▼────────┐
    │  RDS (PG)   │
    │  ElastiCache│
    └─────────────┘
```

### 1. AWS ECS Deployment

#### Task Definition
```json
{
  "family": "payment-orchestration",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "payment-orchestration",
      "image": "your-ecr-repo/payment-orchestration:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "prod"
        }
      ],
      "secrets": [
        {
          "name": "DB_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:region:account:secret:db-password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/payment-orchestration",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      },
      "healthCheck": {
        "command": [
          "CMD-SHELL",
          "curl -f http://localhost:8080/actuator/health || exit 1"
        ],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      }
    }
  ]
}
```

#### Deploy Command
```bash
# Register task definition
aws ecs register-task-definition --cli-input-json file://task-definition.json

# Update service
aws ecs update-service \
  --cluster payment-cluster \
  --service payment-orchestration \
  --task-definition payment-orchestration:latest \
  --force-new-deployment
```

### 2. Kubernetes Deployment

#### Deployment YAML
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-orchestration
  namespace: payments
spec:
  replicas: 3
  selector:
    matchLabels:
      app: payment-orchestration
  template:
    metadata:
      labels:
        app: payment-orchestration
    spec:
      containers:
      - name: payment-orchestration
        image: your-registry/payment-orchestration:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: DB_HOST
          valueFrom:
            configMapKeyRef:
              name: payment-config
              key: db.host
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: payment-secrets
              key: db.password
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: payment-orchestration
  namespace: payments
spec:
  selector:
    app: payment-orchestration
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```

#### Deploy to Kubernetes
```bash
# Create namespace
kubectl create namespace payments

# Create ConfigMap
kubectl create configmap payment-config \
  --from-literal=db.host=postgres.payments.svc.cluster.local \
  -n payments

# Create Secret
kubectl create secret generic payment-secrets \
  --from-literal=db.password=your-password \
  -n payments

# Apply deployment
kubectl apply -f k8s/deployment.yaml

# Check status
kubectl get pods -n payments
kubectl logs -f deployment/payment-orchestration -n payments
```

---

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `DB_HOST` | PostgreSQL host | localhost | Yes |
| `DB_PORT` | PostgreSQL port | 5432 | Yes |
| `DB_NAME` | Database name | payment_orchestration | Yes |
| `DB_USERNAME` | Database user | postgres | Yes |
| `DB_PASSWORD` | Database password | - | Yes |
| `REDIS_HOST` | Redis host | localhost | Yes |
| `REDIS_PORT` | Redis port | 6379 | Yes |
| `REDIS_PASSWORD` | Redis password | - | No |
| `SERVER_PORT` | Application port | 8080 | No |
| `ZIPKIN_URL` | Zipkin endpoint | http://localhost:9411 | No |
| `ZIPKIN_ENABLED` | Enable tracing | true | No |
| `PROVIDER_A_URL` | Provider A endpoint | - | Yes |
| `PROVIDER_A_API_KEY` | Provider A API key | - | Yes |
| `PROVIDER_B_URL` | Provider B endpoint | - | Yes |
| `PROVIDER_B_API_KEY` | Provider B API key | - | Yes |
| `WEBHOOK_SECRET` | Webhook signature secret | - | Yes |

### Application Profiles

#### Development (`application-dev.yml`)
- H2 in-memory database
- Verbose logging
- Disabled security features
- Mock providers

#### Staging (`application-staging.yml`)
- Real database (RDS)
- Moderate logging
- Enabled security
- Test provider endpoints

#### Production (`application-prod.yml`)
- Production database
- Minimal logging
- Full security enabled
- Production provider endpoints
- Connection pooling optimized
- Circuit breakers enabled

---

## Monitoring & Observability

### Prometheus Metrics
Access metrics at: `http://localhost:8080/actuator/prometheus`

Key metrics:
- `payment_requests_total` - Total payment requests
- `payment_success_total` - Successful payments
- `payment_failure_total` - Failed payments
- `payment_duration_seconds` - Payment processing time
- `provider_calls_total` - Provider API calls
- `circuit_breaker_state` - Circuit breaker status

### Grafana Dashboards
1. Access Grafana: http://localhost:3000
2. Login: admin/admin
3. Add Prometheus data source: http://prometheus:9090
4. Import dashboard: Use dashboard ID or JSON

### Distributed Tracing
Access Zipkin UI: http://localhost:9411

Features:
- End-to-end request tracing
- Service dependency graph
- Latency analysis
- Error tracking

### Logging
Logs are written to:
- Console (stdout)
- File: `logs/payment-orchestration.log`
- CloudWatch (production)

Log levels:
- `ERROR`: Critical errors requiring immediate attention
- `WARN`: Warning conditions
- `INFO`: Informational messages
- `DEBUG`: Detailed debug information

---

## Troubleshooting

### Common Issues

#### 1. Application Won't Start
```bash
# Check logs
docker-compose logs payment-orchestration

# Common causes:
# - Database not ready: Wait for PostgreSQL to be healthy
# - Port conflict: Change SERVER_PORT
# - Missing environment variables: Check .env file
```

#### 2. Database Connection Failed
```bash
# Test database connectivity
docker exec -it payment-postgres psql -U postgres -d payment_orchestration

# Check connection string
echo $DB_HOST:$DB_PORT/$DB_NAME

# Verify credentials
psql -h localhost -U postgres -d payment_orchestration
```

#### 3. Redis Connection Failed
```bash
# Test Redis connectivity
docker exec -it payment-redis redis-cli ping

# Check Redis logs
docker logs payment-redis

# Test from application host
redis-cli -h localhost -p 6379 ping
```

#### 4. Circuit Breaker Open
```bash
# Check circuit breaker status
curl http://localhost:8080/actuator/circuitbreakers

# Reset circuit breaker (requires custom endpoint)
curl -X POST http://localhost:8080/actuator/circuitbreakers/providerA/reset
```

#### 5. High Memory Usage
```bash
# Check JVM memory
curl http://localhost:8080/actuator/metrics/jvm.memory.used

# Adjust heap size
export JAVA_OPTS="-Xms512m -Xmx2g"
java $JAVA_OPTS -jar app.jar

# Enable heap dump on OOM
export JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
```

### Health Checks

```bash
# Overall health
curl http://localhost:8080/actuator/health

# Liveness probe (Kubernetes)
curl http://localhost:8080/actuator/health/liveness

# Readiness probe (Kubernetes)
curl http://localhost:8080/actuator/health/readiness

# Detailed health
curl http://localhost:8080/actuator/health | jq
```

### Performance Tuning

#### Database Connection Pool
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20      # Adjust based on load
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

#### JVM Tuning
```bash
# G1GC (recommended for containers)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0

# Heap size
-Xms1g -Xmx2g

# GC logging
-Xlog:gc*:file=/tmp/gc.log:time,uptime:filecount=5,filesize=10M
```

---

## Security Checklist

### Pre-Production
- [ ] Change default passwords
- [ ] Rotate API keys
- [ ] Enable HTTPS/TLS
- [ ] Configure firewall rules
- [ ] Enable audit logging
- [ ] Set up secrets management (AWS Secrets Manager, Vault)
- [ ] Configure rate limiting
- [ ] Enable CORS properly
- [ ] Review security headers
- [ ] Scan for vulnerabilities

### Production
- [ ] Monitor security alerts
- [ ] Regular security patches
- [ ] Backup encryption keys
- [ ] Incident response plan
- [ ] Regular security audits
- [ ] PCI-DSS compliance (if applicable)
- [ ] GDPR compliance (if applicable)

---

## Backup & Recovery

### Database Backup
```bash
# Manual backup
docker exec payment-postgres pg_dump -U postgres payment_orchestration > backup.sql

# Automated backup (cron)
0 2 * * * docker exec payment-postgres pg_dump -U postgres payment_orchestration | gzip > /backups/payment_$(date +\%Y\%m\%d).sql.gz

# Restore
docker exec -i payment-postgres psql -U postgres payment_orchestration < backup.sql
```

### Redis Backup
```bash
# Trigger save
docker exec payment-redis redis-cli BGSAVE

# Copy RDB file
docker cp payment-redis:/data/dump.rdb ./redis-backup.rdb
```

---

## Scaling Considerations

### Horizontal Scaling
- Stateless application design
- Session data in Redis
- Database connection pooling
- Load balancer configuration

### Vertical Scaling
- Increase container resources
- Optimize JVM heap size
- Database instance upgrade
- Redis instance upgrade

### Auto-Scaling (Kubernetes)
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: payment-orchestration-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payment-orchestration
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

---

## Support

For issues or questions:
- Check documentation: `/docs`
- Review logs: `docker-compose logs`
- Check metrics: http://localhost:8080/actuator/metrics
- Contact: devops@example.com