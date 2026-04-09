   - Memory usage (gauge)
   - Database connections (gauge)
   - Cache memory (gauge)

---

### 12.2 Business Dashboard

**Purpose**: Business metrics and KPIs

**Panels**:

1. **Volume & Revenue** (Top Row)
   - Payment volume (line chart)
   - Revenue (line chart)
   - Average transaction value (line chart)
   - Success rate (gauge)

2. **Payment Methods** (Second Row)
   - Volume by payment method (pie chart)
   - Success rate by method (bar chart)
   - Revenue by method (bar chart)

3. **Geographic Distribution** (Third Row)
   - Volume by country (map)
   - Success rate by country (table)
   - Top countries by revenue (bar chart)

4. **Provider Performance** (Fourth Row)
   - Volume by provider (pie chart)
   - Cost by provider (bar chart)
   - Success rate by provider (bar chart)

---

### 12.3 Provider Comparison Dashboard

**Purpose**: Compare provider performance

**Panels**:

1. **Success Rates** (Top Row)
   - Success rate comparison (bar chart)
   - Decline rate comparison (bar chart)
   - Timeout rate comparison (bar chart)

2. **Latency Comparison** (Second Row)
   - P50 latency (bar chart)
   - P95 latency (bar chart)
   - P99 latency (bar chart)

3. **Cost Analysis** (Third Row)
   - Average fee per transaction (bar chart)
   - Total fees paid (line chart)
   - Effective rate (bar chart)

4. **Reliability** (Fourth Row)
   - Circuit breaker states (status panel)
   - Failover events (counter)
   - Uptime percentage (gauge)

---

### 12.4 Debugging Dashboard

**Purpose**: Troubleshooting and root cause analysis

**Panels**:

1. **Error Analysis** (Top Row)
   - Errors by type (pie chart)
   - Error rate over time (line chart)
   - Top error codes (table)

2. **Latency Breakdown** (Second Row)
   - Component latency (stacked bar chart)
   - Slowest queries (table)
   - Slowest provider calls (table)

3. **Retry Analysis** (Third Row)
   - Retry attempts distribution (histogram)
   - Retry success rate (gauge)
   - Retries by provider (bar chart)

4. **Idempotency** (Fourth Row)
   - Hit rate (gauge)
   - Duplicate requests (counter)
   - Cache performance (line chart)

---

## 13. Metric Collection Architecture

### 13.1 Collection Flow

```
┌─────────────────────────────────────────────────────────────┐
│              METRIC COLLECTION ARCHITECTURE                  │
└─────────────────────────────────────────────────────────────┘

Application Code
    ↓
[Prometheus Client Library]
    ↓
Expose /metrics endpoint
    ↓
[Prometheus Server] ← Scrapes every 15s
    ↓
Store in TSDB
    ↓
[Grafana] ← Queries Prometheus
    ↓
Display Dashboards
    ↓
[Alertmanager] ← Receives alerts from Prometheus
    ↓
Send notifications (PagerDuty, Slack, Email)
```

### 13.2 Metric Retention

**Retention Policy**:
```yaml
# prometheus.yml

global:
  scrape_interval: 15s
  evaluation_interval: 15s

storage:
  tsdb:
    retention.time: 30d      # Keep raw data for 30 days
    retention.size: 100GB    # Or until 100GB limit

# Downsampling (via recording rules)
recording_rules:
  - name: hourly_aggregates
    interval: 1h
    rules:
      # Aggregate to hourly for long-term storage
      - record: payment_requests:rate1h
        expr: rate(payment_requests_total[1h])
      
      - record: payment_latency:p95_1h
        expr: histogram_quantile(0.95, rate(payment_request_duration_seconds_bucket[1h]))
```

**Long-Term Storage**:
- Raw metrics: 30 days (Prometheus)
- Hourly aggregates: 1 year (Prometheus)
- Daily aggregates: 5 years (External storage like Thanos/Cortex)

---

## 14. SLI/SLO/SLA Framework

### 14.1 Service Level Indicators (SLIs)

**Definition**: Quantitative measures of service level

**Key SLIs**:

1. **Availability SLI**
   ```
   SLI = (Successful Requests / Total Requests) × 100
   
   Successful = HTTP 200/201 responses
   Total = All HTTP responses
   ```

2. **Latency SLI**
   ```
   SLI = (Requests < 500ms / Total Requests) × 100
   
   Target: 95% of requests < 500ms
   ```

3. **Error Rate SLI**
   ```
   SLI = (Failed Requests / Total Requests) × 100
   
   Failed = HTTP 5xx or payment declined
   Target: < 1%
   ```

---

### 14.2 Service Level Objectives (SLOs)

**Definition**: Target values for SLIs

**Payment Orchestration SLOs**:

| SLO | Target | Measurement Window | Error Budget |
|-----|--------|-------------------|--------------|
| Availability | 99.9% | 30 days | 43 minutes |
| Latency (P95) | < 500ms | 7 days | 5% of requests |
| Error Rate | < 1% | 24 hours | 1% of requests |
| Success Rate | > 99% | 24 hours | 1% of requests |

**Error Budget Calculation**:
```
Error Budget = (1 - SLO) × Total Requests

Example (99.9% availability):
- Total requests in 30 days: 100M
- Error budget: (1 - 0.999) × 100M = 100K failed requests
- Time budget: 30 days × 0.1% = 43 minutes downtime
```

**Error Budget Policy**:
- Budget > 50%: Focus on new features
- Budget 25-50%: Balance features and reliability
- Budget < 25%: Freeze features, focus on reliability
- Budget exhausted: Incident response mode

---

### 14.3 Service Level Agreements (SLAs)

**Definition**: Contractual commitments to customers

**Payment Orchestration SLA**:

```yaml
sla:
  availability:
    commitment: 99.9%
    measurement_window: monthly
    exclusions:
      - Scheduled maintenance (< 4 hours/quarter)
      - Customer-caused issues
      - Force majeure events
    
  latency:
    commitment: P95 < 1 second
    measurement_window: monthly
    
  support:
    response_time:
      critical: 15 minutes
      high: 1 hour
      medium: 4 hours
      low: 24 hours
    
  credits:
    - availability: 99.0-99.9% → 10% credit
    - availability: 95.0-99.0% → 25% credit
    - availability: < 95.0% → 50% credit
```

---

## 15. Monitoring Best Practices

### 15.1 The Four Golden Rules

1. **For every metric, know why it matters**
   - Don't collect metrics "just in case"
   - Each metric should inform a decision
   - Remove unused metrics

2. **Alert on symptoms, not causes**
   - Alert: "Error rate > 1%" (symptom)
   - Not: "Database CPU > 80%" (cause)
   - Investigate causes during incident response

3. **Keep cardinality low**
   - Avoid high-cardinality labels (user_id, transaction_id)
   - Use aggregation for high-cardinality data
   - Monitor cardinality growth

4. **Test your alerts**
   - Regularly trigger test alerts
   - Verify alert routing
   - Measure time to acknowledge

---

### 15.2 Metric Hygiene

**Do's**:
- Use consistent naming conventions
- Document metric meanings
- Set appropriate retention periods
- Use appropriate metric types
- Add helpful labels

**Don'ts**:
- Don't use metrics for logging
- Don't create metrics per user/transaction
- Don't ignore stale metrics
- Don't alert on everything
- Don't forget to clean up old metrics

---

### 15.3 Alert Fatigue Prevention

**Strategies**:

1. **Reduce Alert Volume**
   - Increase thresholds gradually
   - Use longer evaluation periods
   - Group related alerts
   - Implement alert suppression

2. **Improve Alert Quality**
   - Make alerts actionable
   - Include runbook links
   - Add context in annotations
   - Test alert accuracy

3. **Alert Routing**
   - Route by severity
   - Use escalation policies
   - Implement on-call rotations
   - Provide clear ownership

4. **Alert Review**
   - Weekly alert review meetings
   - Track alert response times
   - Measure false positive rate
   - Continuously tune thresholds

---

## 16. Incident Response Metrics

### 16.1 MTTR (Mean Time To Recovery)

**What to Measure**: Time from incident start to resolution

**Metric Definition**:
```prometheus
# Histogram: Incident duration
incident_duration_seconds{
    severity="critical|high|medium|low",
    service="payment-orchestration"
}

# Derived: MTTR
avg(incident_duration_seconds{severity="critical"})
```

**Why Important**:
- Measure incident response effectiveness
- Track improvement over time
- Identify bottlenecks in response process

**Target SLIs**:
- Critical incidents: < 15 minutes
- High severity: < 1 hour
- Medium severity: < 4 hours

---

### 16.2 MTTD (Mean Time To Detect)

**What to Measure**: Time from issue occurrence to detection

**Metric Definition**:
```prometheus
# Histogram: Detection time
incident_detection_duration_seconds{
    detection_method="alert|customer_report|monitoring"
}
```

**Why Important**:
- Measure monitoring effectiveness
- Improve alert coverage
- Reduce customer impact

**Target SLIs**:
- Alert-based detection: < 2 minutes
- Monitoring-based: < 5 minutes
- Customer-reported: < 15 minutes (goal: eliminate)

---

### 16.3 Incident Frequency

**What to Measure**: Number of incidents over time

**Metric Definition**:
```prometheus
# Counter: Incidents
incidents_total{
    severity="critical|high|medium|low",
    root_cause="provider|database|code|config"
}

# Derived: Incident rate
rate(incidents_total{severity="critical"}[30d])
```

**Why Important**:
- Track system stability
- Identify recurring issues
- Measure reliability improvements

**Target SLIs**:
- Critical incidents: < 1 per month
- High severity: < 5 per month
- Total incidents: Decreasing trend

---

## 17. Cost Optimization Metrics

### 17.1 Infrastructure Cost

**What to Measure**: Cloud infrastructure spend

**Metrics**:
```prometheus
# Gauge: Monthly cost
infrastructure_cost_dollars{
    service="compute|database|cache|network|storage"
}

# Derived: Cost per transaction
infrastructure_cost_dollars / payment_volume_total
```

**Why Important**:
- Track spending trends
- Optimize resource allocation
- Justify infrastructure investments

**Target SLIs**:
- Cost per transaction: < $0.01
- Month-over-month growth: < 20%
- Cost efficiency: Improving

---

### 17.2 Provider Cost

**What to Measure**: Payment provider fees

**Metrics**:
```prometheus
# Counter: Provider fees
provider_fees_total_dollars{
    provider="stripe|paypal|adyen"
}

# Derived: Effective rate
provider_fees_total_dollars / payment_value_total_dollars
```

**Why Important**:
- Compare provider costs
- Optimize routing for cost
- Negotiate better rates

**Target SLIs**:
- Average effective rate: < 3%
- Cost variance: < 10% month-over-month

---

## 18. Summary

### 18.1 Critical Metrics Checklist

**Must-Have Metrics**:

✅ **Golden Signals**
- [ ] Request rate (traffic)
- [ ] Error rate
- [ ] Latency percentiles (P50, P95, P99)
- [ ] Saturation (CPU, memory, connections)

✅ **Availability**
- [ ] Success rate
- [ ] Uptime percentage
- [ ] Error rate by type

✅ **Performance**
- [ ] End-to-end latency
- [ ] Component latency breakdown
- [ ] Database query latency
- [ ] Provider API latency

✅ **Reliability**
- [ ] Retry rate and success rate
- [ ] Failover rate
- [ ] Circuit breaker states
- [ ] Provider success rates

✅ **Idempotency**
- [ ] Cache hit rate
- [ ] Duplicate request rate
- [ ] Cache operation latency

✅ **Business**
- [ ] Payment volume
- [ ] Revenue
- [ ] Success rate by payment method
- [ ] Cost per transaction

---

### 18.2 Metric Priorities

**P0 (Critical - Must Have)**:
- Request rate
- Error rate
- Latency (P95, P99)
- Success rate
- Provider success rates
- Circuit breaker states

**P1 (High - Should Have)**:
- Retry metrics
- Failover metrics
- Idempotency hit rate
- Database metrics
- Cache metrics
- Component latency breakdown

**P2 (Medium - Nice to Have)**:
- Business metrics
- Cost metrics
- Incident metrics
- Geographic distribution

---

### 18.3 Implementation Roadmap

**Phase 1: Foundation (Week 1-2)**
- Implement golden signals
- Set up Prometheus + Grafana
- Create operations dashboard
- Configure basic alerts

**Phase 2: Deep Observability (Week 3-4)**
- Add component-level metrics
- Implement distributed tracing
- Create debugging dashboard
- Tune alert thresholds

**Phase 3: Business Intelligence (Week 5-6)**
- Add business metrics
- Create business dashboard
- Implement cost tracking
- Set up SLO monitoring

**Phase 4: Optimization (Week 7-8)**
- Review and optimize metrics
- Remove unused metrics
- Improve alert quality
- Document runbooks

---

### 18.4 Key Takeaways

🎯 **Measure What Matters**: Focus on metrics that drive decisions  
🎯 **Alert on Symptoms**: Alert on user-facing issues, not internal states  
🎯 **Keep It Simple**: Start with golden signals, add complexity gradually  
🎯 **Automate Everything**: Metrics, alerts, dashboards should be code  
🎯 **Continuous Improvement**: Regularly review and refine metrics  
🎯 **Document Everything**: Every metric should have clear documentation  
🎯 **Test Your Monitoring**: Regularly test alerts and dashboards  
🎯 **Learn from Incidents**: Use incidents to improve monitoring  

---

## Appendix A: Prometheus Query Examples

### A.1 Common Queries

**Request Rate**:
```promql
# Requests per second
rate(payment_requests_total[5m])

# Requests per second by status
sum(rate(payment_requests_total[5m])) by (status)
```

**Error Rate**:
```promql
# Error rate percentage
(rate(payment_errors_total[5m]) / rate(payment_requests_total[5m])) * 100

# Error rate by provider
sum(rate(payment_errors_total[5m])) by (provider) /
sum(rate(payment_requests_total[5m])) by (provider) * 100
```

**Latency Percentiles**:
```promql
# P95 latency
histogram_quantile(0.95, rate(payment_request_duration_seconds_bucket[5m]))

# P99 latency by provider
histogram_quantile(0.99, 
  sum(rate(payment_request_duration_seconds_bucket[5m])) by (provider, le)
)
```

**Success Rate**:
```promql
# Overall success rate
sum(rate(payment_requests_total{status="succeeded"}[5m])) /
sum(rate(payment_requests_total[5m])) * 100

# Success rate by provider
sum(rate(provider_requests_total{status="succeeded"}[5m])) by (provider) /
sum(rate(provider_requests_total[5m])) by (provider) * 100
```

**Retry Rate**:
```promql
# Retry rate
sum(rate(payment_retry_attempts_total[5m])) /
sum(rate(payment_requests_total[5m])) * 100

# Retry success rate
sum(rate(payment_retry_outcomes_total{outcome="success"}[5m])) /
sum(rate(payment_retry_attempts_total[5m])) * 100
```

---

## Appendix B: Grafana Dashboard JSON

### B.1 Operations Dashboard Template

```json
{
  "dashboard": {
    "title": "Payment Orchestration - Operations",
    "tags": ["payment", "operations"],
    "timezone": "UTC",
    "panels": [
      {
        "title": "Request Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(rate(payment_requests_total[5m]))",
            "legendFormat": "Total Requests/sec"
          }
        ]
      },
      {
        "title": "Error Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "(rate(payment_errors_total[5m]) / rate(payment_requests_total[5m])) * 100",
            "legendFormat": "Error Rate %"
          }
        ],
        "alert": {
          "conditions": [
            {
              "evaluator": {
                "params": [1],
                "type": "gt"
              },
              "operator": {
                "type": "and"
              },
              "query": {
                "params": ["A", "5m", "now"]
              },
              "reducer": {
                "params": [],
                "type": "avg"
              },
              "type": "query"
            }
          ],
          "frequency": "1m",
          "handler": 1,
          "name": "High Error Rate",
          "noDataState": "no_data",
          "notifications": []
        }
      },
      {
        "title": "Latency Percentiles",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.50, rate(payment_request_duration_seconds_bucket[5m]))",
            "legendFormat": "P50"
          },
          {
            "expr": "histogram_quantile(0.95, rate(payment_request_duration_seconds_bucket[5m]))",
            "legendFormat": "P95"
          },
          {
            "expr": "histogram_quantile(0.99, rate(payment_request_duration_seconds_bucket[5m]))",
            "legendFormat": "P99"
          }
        ]
      },
      {
        "title": "Success Rate",
        "type": "singlestat",
        "targets": [
          {
            "expr": "sum(rate(payment_requests_total{status=\"succeeded\"}[5m])) / sum(rate(payment_requests_total[5m])) * 100"
          }
        ],
        "format": "percent",
        "thresholds": "95,99"
      }
    ]
  }
}
```

---

## Appendix C: Alert Runbooks

### C.1 High Error Rate Runbook

**Alert**: `HighErrorRate`  
**Severity**: Critical  
**Threshold**: Error rate > 5% for 2 minutes

**Investigation Steps**:

1. **Check Dashboard**
   - Open operations dashboard
   - Identify error spike timing
   - Check which providers are affected

2. **Query Error Details**
   ```promql
   # Top error codes
   topk(10, sum(rate(payment_errors_total[5m])) by (error_code))
   
   # Errors by provider
   sum(rate(payment_errors_total[5m])) by (provider)
   ```

3. **Check Provider Status**
   - Visit provider status pages
   - Check circuit breaker states
   - Review recent deployments

4. **Mitigation**
   - If provider issue: Circuit breaker should auto-failover
   - If code issue: Rollback recent deployment
   - If config issue: Revert configuration change

5. **Communication**
   - Update incident channel
   - Notify stakeholders if customer-facing
   - Post status page update

---

**Document Version**: 1.0.0  
**Last Updated**: 2026-04-09  
**Status**: Production Ready  
**Next Steps**: Implement metrics, deploy dashboards, configure alerts