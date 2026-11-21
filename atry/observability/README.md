# 可观测性栈（Prometheus + Grafana + Jaeger）

可选组件，默认不启动；只绑定回环地址。应用侧已暴露 `/actuator/prometheus`（管理端口 9091）。

## 启动

```bash
cp .env.docker.example .env.docker   # 若还没有
# 至少设置 DB_PASSWORD / MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / JWT_SECRET / GRAFANA_ADMIN_PASSWORD
docker compose -f compose.yml -f compose.observability.yml --profile observability up -d
```

- Grafana：<http://127.0.0.1:13000>（默认 admin，密码取 `GRAFANA_ADMIN_PASSWORD`），已预置 `atry overview` 看板。
- Prometheus：<http://127.0.0.1:19090>，抓取 `backend:9091/actuator/prometheus`，加载 `alerts.yml`。
- Jaeger：<http://127.0.0.1:16686>（需 `TRACING_ENABLED=true`）。

## 看板内容

JVM/GC/线程、Hikari 连接池、HTTP QPS/5xx/p95、支付提交/支付/履约计数、存储 URL 缓存与 Redis 降级、
限流命中（`atry_rate_limit_blocked_total`）、分布式锁获取/失败、LLM token 速率、`atry.method` p95。

## 告警规则（`prometheus/alerts.yml`）

- `AtryBackendDown`：backend 抓取失败 2 分钟（critical）
- `AtryHigh5xxRate`：5xx 占比 >5% 持续 5 分钟
- `AtryP95LatencyHigh`：HTTP p95 >1s 持续 10 分钟
- `AtryHeapPressure`：堆使用率 >90% 持续 10 分钟
- `AtryLockFailures`：10 分钟锁失败 >5 次
- `AtryRateLimitSpike`：限流命中持续升高（info）

Prometheus 只负责展示/触发；要外发通知需再接 Alertmanager（未包含）。

## 校验

```bash
python3 -m json.tool observability/grafana/dashboards/atry-overview.json >/dev/null
docker compose -f compose.yml -f compose.observability.yml config -q
```
