# Payment Orchestration System - Database Schema Design

## Document Information
- **Version**: 1.0.0
- **Last Updated**: 2026-04-09
- **Author**: Backend Data Architecture Team
- **Database**: PostgreSQL 14+
- **Status**: Production Ready

---

## Table of Contents
1. [Overview](#1-overview)
2. [Schema Design Principles](#2-schema-design-principles)
3. [Core Tables](#3-core-tables)
4. [Idempotency Store](#4-idempotency-store)
5. [Audit Tables](#5-audit-tables)
6. [Indexing Strategy](#6-indexing-strategy)
7. [Data Consistency](#7-data-consistency)
8. [Partitioning Strategy](#8-partitioning-strategy)
9. [Backup & Recovery](#9-backup--recovery)
10. [Migration Strategy](#10-migration-strategy)

---

## 1. Overview

### 1.1 Database Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  DATABASE ARCHITECTURE                       │
└─────────────────────────────────────────────────────────────┘

Primary Database (PostgreSQL)
├─ Transactional Data
│  ├─ payments (main table)
│  ├─ transactions (provider transactions)
│  ├─ retry_attempts (retry tracking)
│  └─ provider_responses (provider data)
│
├─ Configuration Data
│  ├─ routing_rules
│  ├─ provider_configs
│  └─ circuit_breaker_states
│
├─ Audit Data
│  ├─ audit_events (compliance log)
│  └─ payment_events (domain events)
│
└─ Idempotency
   └─ idempotency_keys (Redis + PostgreSQL)

Cache Layer (Redis)
├─ Idempotency keys (24h TTL)
├─ Circuit breaker states
└─ Provider health status
```

### 1.2 Design Goals

- **ACID Compliance**: Full transactional integrity
- **High Performance**: Optimized for 1000+ TPS
- **Scalability**: Horizontal partitioning support
- **Auditability**: Complete audit trail (7-year retention)
- **Data Integrity**: Foreign keys, constraints, and triggers
- **Query Performance**: Strategic indexing

---

## 2. Schema Design Principles

### 2.1 Naming Conventions

- **Tables**: Plural nouns, snake_case (`payments`, `audit_events`)
- **Columns**: snake_case (`transaction_id`, `created_at`)
- **Indexes**: `idx_<table>_<columns>` (`idx_payments_customer_id`)
- **Foreign Keys**: `fk_<table>_<referenced_table>` (`fk_transactions_payments`)
- **Constraints**: `chk_<table>_<condition>` (`chk_payments_amount_positive`)

### 2.2 Data Types

| Type | Usage | Example |
|------|-------|---------|
| `UUID` | Primary keys, unique identifiers | `transaction_id` |
| `BIGINT` | Amounts (in cents) | `amount` |
| `VARCHAR(3)` | Currency codes | `currency` |
| `TIMESTAMP WITH TIME ZONE` | Timestamps | `created_at` |
| `JSONB` | Flexible metadata | `metadata` |
| `ENUM` | Fixed value sets | `payment_status` |

### 2.3 Timestamp Strategy

All tables include:
- `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`
- `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`

Automatic update trigger:
```sql
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## 3. Core Tables

### 3.1 Payments Table

**Purpose**: Main payment records

```sql
CREATE TABLE payments (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Business Identifiers
    transaction_id VARCHAR(50) UNIQUE NOT NULL,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    
    -- Customer Information
    customer_id VARCHAR(100) NOT NULL,
    customer_email VARCHAR(255),
    
    -- Payment Details
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    
    -- Status & Provider
    status VARCHAR(20) NOT NULL,
    provider VARCHAR(50),
    provider_transaction_id VARCHAR(255),
    
    -- Card Information (encrypted)
    card_last4 VARCHAR(4),
    card_brand VARCHAR(20),
    card_expiry_month INTEGER,
    card_expiry_year INTEGER,
    
    -- Address Information
    billing_address JSONB,
    
    -- Metadata
    metadata JSONB,
    
    -- Retry Information
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Constraints
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_currency_length CHECK (LENGTH(currency) = 3),
    CONSTRAINT chk_payments_retry_count CHECK (retry_count >= 0),
    CONSTRAINT chk_payments_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 
        'CANCELLED', 'REFUNDED', 'DISPUTED'
    ))
);

-- Indexes
CREATE INDEX idx_payments_customer_id ON payments(customer_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_provider ON payments(provider);
CREATE INDEX idx_payments_created_at ON payments(created_at DESC);
CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);
CREATE INDEX idx_payments_transaction_id ON payments(transaction_id);

-- Composite indexes for common queries
CREATE INDEX idx_payments_customer_status ON payments(customer_id, status);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);

-- Partial indexes for active payments
CREATE INDEX idx_payments_active ON payments(status, created_at) 
WHERE status IN ('PENDING', 'PROCESSING');

-- Update trigger
CREATE TRIGGER update_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Comments
COMMENT ON TABLE payments IS 'Main payment records with full transaction details';
COMMENT ON COLUMN payments.amount IS 'Amount in smallest currency unit (cents)';
COMMENT ON COLUMN payments.idempotency_key IS 'Client-provided idempotency key for duplicate prevention';
```

### 3.2 Transactions Table

**Purpose**: Provider-specific transaction details

```sql
CREATE TABLE transactions (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Foreign Key
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    
    -- Provider Information
    provider VARCHAR(50) NOT NULL,
    provider_transaction_id VARCHAR(255),
    provider_status VARCHAR(50),
    
    -- Request/Response
    request_payload JSONB NOT NULL,
    response_payload JSONB,
    
    -- Timing
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms INTEGER,
    
    -- Result
    success BOOLEAN,
    error_code VARCHAR(100),
    error_message TEXT,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_transactions_payment_id ON transactions(payment_id);
CREATE INDEX idx_transactions_provider ON transactions(provider);
CREATE INDEX idx_transactions_provider_transaction_id ON transactions(provider_transaction_id);
CREATE INDEX idx_transactions_success ON transactions(success);
CREATE INDEX idx_transactions_started_at ON transactions(started_at DESC);

-- Update trigger
CREATE TRIGGER update_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE transactions IS 'Provider-specific transaction attempts and responses';
```

### 3.3 Retry Attempts Table

**Purpose**: Track retry history

```sql
CREATE TABLE retry_attempts (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Foreign Key
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    
    -- Retry Information
    attempt_number INTEGER NOT NULL,
    provider VARCHAR(50) NOT NULL,
    
    -- Result
    success BOOLEAN NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    
    -- Timing
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    duration_ms INTEGER,
    
    -- Next Retry
    next_retry_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT chk_retry_attempts_attempt_number CHECK (attempt_number > 0)
);

-- Indexes
CREATE INDEX idx_retry_attempts_payment_id ON retry_attempts(payment_id);
CREATE INDEX idx_retry_attempts_provider ON retry_attempts(provider);
CREATE INDEX idx_retry_attempts_success ON retry_attempts(success);
CREATE INDEX idx_retry_attempts_next_retry ON retry_attempts(next_retry_at) 
WHERE next_retry_at IS NOT NULL;

COMMENT ON TABLE retry_attempts IS 'Historical record of all retry attempts';
```

### 3.4 Provider Responses Table

**Purpose**: Store raw provider responses for debugging

```sql
CREATE TABLE provider_responses (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Foreign Key
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    
    -- Provider Information
    provider VARCHAR(50) NOT NULL,
    
    -- HTTP Details
    http_status INTEGER,
    http_headers JSONB,
    
    -- Response Data
    response_body JSONB NOT NULL,
    
    -- Timing
    response_time_ms INTEGER,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_provider_responses_payment_id ON provider_responses(payment_id);
CREATE INDEX idx_provider_responses_provider ON provider_responses(provider);
CREATE INDEX idx_provider_responses_created_at ON provider_responses(created_at DESC);

COMMENT ON TABLE provider_responses IS 'Raw provider API responses for debugging and compliance';
```

---

## 4. Idempotency Store

### 4.1 Idempotency Keys Table

**Purpose**: Prevent duplicate payment processing

```sql
CREATE TABLE idempotency_keys (
    -- Primary Key
    idempotency_key VARCHAR(255) PRIMARY KEY,
    
    -- Request Information
    request_hash VARCHAR(64) NOT NULL,
    request_payload JSONB NOT NULL,
    
    -- Response Information
    response_status INTEGER,
    response_payload JSONB,
    
    -- Associated Payment
    payment_id UUID REFERENCES payments(id),
    transaction_id VARCHAR(50),
    
    -- Status
    status VARCHAR(20) NOT NULL,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Constraints
    CONSTRAINT chk_idempotency_status CHECK (status IN (
        'PROCESSING', 'COMPLETED', 'FAILED'
    ))
);

-- Indexes
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);
CREATE INDEX idx_idempotency_keys_payment_id ON idempotency_keys(payment_id);
CREATE INDEX idx_idempotency_keys_status ON idempotency_keys(status);

-- Cleanup expired keys (run daily)
CREATE OR REPLACE FUNCTION cleanup_expired_idempotency_keys()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM idempotency_keys
    WHERE expires_at < NOW();
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE idempotency_keys IS 'Idempotency key store with 24-hour TTL';
```

---

## 5. Audit Tables

### 5.1 Audit Events Table

**Purpose**: Compliance and audit trail (7-year retention)

```sql
CREATE TABLE audit_events (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event Information
    event_id VARCHAR(50) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    
    -- Associated Payment
    payment_id UUID REFERENCES payments(id),
    transaction_id VARCHAR(50),
    
    -- Actor Information
    actor_id VARCHAR(100),
    actor_type VARCHAR(50),
    ip_address INET,
    user_agent TEXT,
    
    -- Event Data
    event_data JSONB NOT NULL,
    
    -- Timestamps
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_audit_events_payment_id ON audit_events(payment_id);
CREATE INDEX idx_audit_events_transaction_id ON audit_events(transaction_id);
CREATE INDEX idx_audit_events_event_type ON audit_events(event_type);
CREATE INDEX idx_audit_events_occurred_at ON audit_events(occurred_at DESC);
CREATE INDEX idx_audit_events_actor_id ON audit_events(actor_id);

-- Partitioning by month (for 7-year retention)
CREATE TABLE audit_events_y2026m04 PARTITION OF audit_events
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

COMMENT ON TABLE audit_events IS 'Immutable audit log for compliance (7-year retention)';
```

### 5.2 Payment Events Table

**Purpose**: Domain events for event-driven architecture

```sql
CREATE TABLE payment_events (
    -- Primary Key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event Information
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    
    -- Event Data
    event_data JSONB NOT NULL,
    
    -- Metadata
    correlation_id UUID,
    causation_id UUID,
    
    -- Publishing Status
    published BOOLEAN DEFAULT FALSE,
    published_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_payment_events_aggregate_id ON payment_events(aggregate_id);
CREATE INDEX idx_payment_events_event_type ON payment_events(event_type);
CREATE INDEX idx_payment_events_published ON payment_events(published) WHERE NOT published;
CREATE INDEX idx_payment_events_occurred_at ON payment_events(occurred_at DESC);

COMMENT ON TABLE payment_events IS 'Domain events for event-driven architecture';
```

---

## 6. Indexing Strategy

### 6.1 Index Types

**B-Tree Indexes** (default):
- Primary keys
- Foreign keys
- Equality and range queries

**Partial Indexes**:
```sql
-- Only index active payments
CREATE INDEX idx_payments_active ON payments(status, created_at) 
WHERE status IN ('PENDING', 'PROCESSING');

-- Only index unpublished events
CREATE INDEX idx_payment_events_unpublished ON payment_events(created_at)
WHERE NOT published;
```

**GIN Indexes** (for JSONB):
```sql
-- Index metadata for fast JSON queries
CREATE INDEX idx_payments_metadata_gin ON payments USING GIN (metadata);

-- Index event data
CREATE INDEX idx_audit_events_data_gin ON audit_events USING GIN (event_data);
```

**Composite Indexes**:
```sql
-- Common query patterns
CREATE INDEX idx_payments_customer_status_created 
ON payments(customer_id, status, created_at DESC);

CREATE INDEX idx_transactions_payment_provider 
ON transactions(payment_id, provider);
```

### 6.2 Index Maintenance

```sql
-- Analyze tables after bulk operations
ANALYZE payments;
ANALYZE transactions;

-- Reindex if needed
REINDEX TABLE payments;

-- Monitor index usage
SELECT 
    schemaname,
    tablename,
    indexname,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan ASC;
```

---

## 7. Data Consistency

### 7.1 Foreign Key Constraints

```sql
-- Cascade deletes for dependent data
ALTER TABLE transactions
ADD CONSTRAINT fk_transactions_payments
FOREIGN KEY (payment_id) REFERENCES payments(id)
ON DELETE CASCADE;

-- Prevent orphaned records
ALTER TABLE retry_attempts
ADD CONSTRAINT fk_retry_attempts_payments
FOREIGN KEY (payment_id) REFERENCES payments(id)
ON DELETE CASCADE;
```

### 7.2 Check Constraints

```sql
-- Ensure positive amounts
ALTER TABLE payments
ADD CONSTRAINT chk_payments_amount_positive
CHECK (amount > 0);

-- Validate currency codes
ALTER TABLE payments
ADD CONSTRAINT chk_payments_currency_length
CHECK (LENGTH(currency) = 3);

-- Ensure valid status transitions
CREATE OR REPLACE FUNCTION validate_payment_status_transition()
RETURNS TRIGGER AS $$
BEGIN
    -- PENDING can transition to PROCESSING or CANCELLED
    IF OLD.status = 'PENDING' AND NEW.status NOT IN ('PROCESSING', 'CANCELLED') THEN
        RAISE EXCEPTION 'Invalid status transition from PENDING to %', NEW.status;
    END IF;
    
    -- PROCESSING can transition to SUCCEEDED, FAILED, or CANCELLED
    IF OLD.status = 'PROCESSING' AND NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Invalid status transition from PROCESSING to %', NEW.status;
    END IF;
    
    -- SUCCEEDED can only transition to REFUNDED or DISPUTED
    IF OLD.status = 'SUCCEEDED' AND NEW.status NOT IN ('REFUNDED', 'DISPUTED') THEN
        RAISE EXCEPTION 'Invalid status transition from SUCCEEDED to %', NEW.status;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER validate_payment_status
    BEFORE UPDATE OF status ON payments
    FOR EACH ROW
    EXECUTE FUNCTION validate_payment_status_transition();
```

### 7.3 Transactions

```kotlin
// Example: Atomic payment creation
@Transactional
fun createPayment(request: CreatePaymentRequest): Payment {
    // 1. Create payment record
    val payment = paymentRepository.save(
        Payment(
            transactionId = generateTransactionId(),
            idempotencyKey = request.idempotencyKey,
            amount = request.amount,
            currency = request.currency,
            status = PaymentStatus.PENDING
        )
    )
    
    // 2. Create idempotency record
    idempotencyRepository.save(
        IdempotencyKey(
            key = request.idempotencyKey,
            paymentId = payment.id,
            status = "PROCESSING",
            expiresAt = LocalDateTime.now().plusHours(24)
        )
    )
    
    // 3. Create audit event
    auditEventRepository.save(
        AuditEvent(
            eventType = "PAYMENT_INITIATED",
            paymentId = payment.id,
            eventData = objectMapper.writeValueAsString(request)
        )
    )
    
    return payment
}
```

---

## 8. Partitioning Strategy

### 8.1 Time-Based Partitioning

**Audit Events** (monthly partitions):
```sql
-- Create parent table
CREATE TABLE audit_events (
    id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- ... other columns
) PARTITION BY RANGE (occurred_at);

-- Create partitions
CREATE TABLE audit_events_y2026m01 PARTITION OF audit_events
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE audit_events_y2026m02 PARTITION OF audit_events
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

-- Automatic partition creation
CREATE OR REPLACE FUNCTION create_audit_events_partition()
RETURNS void AS $$
DECLARE
    partition_date DATE;
    partition_name TEXT;
    start_date TEXT;
    end_date TEXT;
BEGIN
    partition_date := DATE_TRUNC('month', NOW() + INTERVAL '1 month');
    partition_name := 'audit_events_y' || TO_CHAR(partition_date, 'YYYY') || 'm' || TO_CHAR(partition_date, 'MM');
    start_date := TO_CHAR(partition_date, 'YYYY-MM-DD');
    end_date := TO_CHAR(partition_date + INTERVAL '1 month', 'YYYY-MM-DD');
    
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
        partition_name, start_date, end_date);
END;
$$ LANGUAGE plpgsql;
```

### 8.2 List-Based Partitioning

**Payments by Status**:
```sql
CREATE TABLE payments (
    -- columns
) PARTITION BY LIST (status);

CREATE TABLE payments_active PARTITION OF payments
    FOR VALUES IN ('PENDING', 'PROCESSING');

CREATE TABLE payments_completed PARTITION OF payments
    FOR VALUES IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

CREATE TABLE payments_post_processing PARTITION OF payments
    FOR VALUES IN ('REFUNDED', 'DISPUTED');
```

---

## 9. Backup & Recovery

### 9.1 Backup Strategy

**Full Backup** (daily):
```bash
pg_dump -h localhost -U postgres -d payment_orchestration \
    -F c -b -v -f backup_$(date +%Y%m%d).dump
```

**Incremental Backup** (WAL archiving):
```sql
-- Enable WAL archiving
ALTER SYSTEM SET wal_level = replica;
ALTER SYSTEM SET archive_mode = on;
ALTER SYSTEM SET archive_command = 'cp %p /backup/wal/%f';
```

**Point-in-Time Recovery**:
```bash
# Restore base backup
pg_restore -h localhost -U postgres -d payment_orchestration backup_20260409.dump

# Apply WAL files
pg_waldump /backup/wal/
```

### 9.2 Retention Policy

| Data Type | Retention | Backup Frequency |
|-----------|-----------|------------------|
| Payments | 7 years | Daily |
| Audit Events | 7 years | Daily |
| Transactions | 7 years | Daily |
| Idempotency Keys | 24 hours | Not backed up |
| Provider Responses | 90 days | Weekly |

---

## 10. Migration Strategy

### 10.1 Flyway Migrations

**Directory Structure**:
```
src/main/resources/db/migration/
├── V1__create_payments_table.sql
├── V2__create_transactions_table.sql
├── V3__create_audit_events_table.sql
├── V4__add_indexes.sql
└── V5__add_partitioning.sql
```

**Example Migration**:
```sql
-- V1__create_payments_table.sql
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id VARCHAR(50) UNIQUE NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_created_at ON payments(created_at DESC);
```

### 10.2 Zero-Downtime Migrations

**Adding a Column**:
```sql
-- Step 1: Add column as nullable
ALTER TABLE payments ADD COLUMN customer_country VARCHAR(2);

-- Step 2: Backfill data (in batches)
UPDATE payments SET customer_country = 'US' WHERE customer_country IS NULL;

-- Step 3: Add NOT NULL constraint
ALTER TABLE payments ALTER COLUMN customer_country SET NOT NULL;
```

**Renaming a Column**:
```sql
-- Step 1: Add new column
ALTER TABLE payments ADD COLUMN customer_email VARCHAR(255);

-- Step 2: Copy data
UPDATE payments SET customer_email = email;

-- Step 3: Deploy code using new column

-- Step 4: Drop old column
ALTER TABLE payments DROP COLUMN email;
```

---

## Appendix

### A. Complete Schema DDL

See `src/main/resources/db/migration/` for complete schema definitions.

### B. Performance Tuning

```sql
-- Analyze query performance
EXPLAIN ANALYZE
SELECT * FROM payments
WHERE customer_id = 'cust_123'
AND status = 'SUCCEEDED'
ORDER BY created_at DESC
LIMIT 10;

-- Monitor table bloat
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename) - pg_relation_size(schemaname||'.'||tablename)) AS external_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

### C. Data Dictionary

Complete data dictionary available in `/docs/data-dictionary.md`

---

**Last Updated**: 2026-04-09  
**Document Version**: 1.0.0