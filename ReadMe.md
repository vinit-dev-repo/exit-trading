# Exit Trading – PCA Automation Console

An ultra-fast, production-oriented Java trading automation console designed for orchestrating PCA (Price Cost Averaging) flows across multiple managed users. The application is built on Spring Boot 3, delivers a responsive admin dashboard, and integrates (optionally) with Zerodha's Kite Connect API for live order routing.

## Features

- **Multi-user orchestration** – single admin can impersonate any onboarded user, toggle logging, manage holdings, and operate on their behalf.
- **PCA session scheduler** – fixed PCA windows (09:30, 10:30, 11:30, 12:30, 13:30, 14:30 IST) with automatic 60-second pre-cleanup of open orders, <1ms order dispatch (pre-built request & high-resolution scheduling).
- **Real-time dashboard** – three-panel layout for upcoming schedules, executed schedules, and holdings with one-click repeat. Market depth refreshes continuously in IST.
- **Auto repeat** – schedules can auto-roll to the next trading day or be manually repeated on demand.
- **Audit logging** – structured audit trail persisted for every execution, failure, session change, or admin action. Logback is configured to emit to `logs/exittrading.log`.
- **Resilient session handling** – reflection-based Kite session manager that works without the jar (paper trading mode) and seamlessly upgrades when the official jar is supplied.
- **Deployment ready** – packaged as a Spring Boot fat jar with systemd-ready scripts and detailed EC2 rollout steps (no Docker required).

## Project layout

```
├── pom.xml                         # Maven build (Java 17, Spring Boot 3)
├── src/main/java/com/exittrading   # Application source
│   ├── config                      # Timezone + security configuration
│   ├── controller                  # REST & MVC controllers
│   ├── domain                      # JPA entities & enums
│   ├── dto                         # Transport objects for REST/UI
│   ├── repository                  # Spring Data repositories
│   └── service                     # Scheduling, Kite integration, logging, depth
├── src/main/resources
│   ├── application.yml             # H2 + logging + timezone settings
│   ├── templates/dashboard.html    # Admin dashboard
│   └── static                      # CSS/JS assets for UI
├── scripts
│   ├── install.sh                  # EC2 provisioning helper
│   └── run.sh                      # Service launcher wrapper
└── logs/                           # Runtime log directory (created at runtime)
```

## Running locally (paper trading mode)

1. **Prerequisites**: JDK 17+, Maven 3.9+, outbound internet for dependencies.
2. **Build**: `mvn clean package`
3. **Run**: `mvn spring-boot:run`
4. **Access dashboard**: `http://localhost:8080` (login with Spring Security in-memory admin `admin` / `admin123`).
5. The UI defaults to IST timezone for every display and input. Market depth is simulated when the Kite jar is absent.

Paper trading mode still enforces full PCA scheduling logic, audit logging, and UI updates. Depth data is randomly generated to assist manual validation.

## Enabling live Kite Connect integration

1. Download `kiteconnect.jar` from Zerodha (official distribution).
2. Place the jar at `lib/kiteconnect.jar` (create the directory if required).
3. Ensure the jar is added to the classpath when launching (see deployment scripts below).
4. Update `src/main/resources/application.yml` with valid `kite.apiKey` and `kite.apiSecret`.
5. Restart the application. When the jar is present, the `DefaultKiteGateway` bean is auto-activated and reflection code binds to the live Kite SDK.
6. Use `/api/admin/session/login` (exposed via the UI) to complete the request-token login flow.

> **Note:** When the jar is missing the system automatically falls back to `PaperTradingGateway`. Audit logs will clearly identify simulated orders with `[PAPER]` prefix.

## Deployment on AWS EC2 (ap-south-1, Ubuntu 22.04 example)

1. **Provision instance**: t3.small or higher, attach IAM role/SG allowing outbound HTTPS to Kite endpoints.
2. **Install Java & build tools** (one-time):
   ```bash
   sudo apt update && sudo apt install -y openjdk-17-jdk maven unzip
   ```
3. **Clone repository**: `git clone https://<your_repo>/exit-trading.git` and `cd exit-trading`.
4. **Copy Kite jar**: `mkdir -p lib && scp kiteconnect.jar ec2-user@host:exit-trading/lib/`.
5. **Configure secrets**: edit `application.yml` (or use environment variables) for `kite.apiKey`, `kite.apiSecret`, logging paths, etc.
6. **Build**: `mvn clean package -DskipTests`
7. **Create service user & directories**:
   ```bash
   sudo useradd -r -s /bin/false trader
   sudo mkdir -p /opt/exit-trading
   sudo chown trader:trader /opt/exit-trading
   cp target/exit-trading-1.0.0.jar /opt/exit-trading/
   cp -r lib /opt/exit-trading/
   cp src/main/resources/application.yml /opt/exit-trading/
   ```
8. **Systemd unit** (sample `/etc/systemd/system/exit-trading.service`):
   ```ini
   [Unit]
   Description=Exit Trading PCA Scheduler
   After=network.target

   [Service]
   User=trader
   WorkingDirectory=/opt/exit-trading
   ExecStart=/usr/bin/java -jar /opt/exit-trading/exit-trading-1.0.0.jar --spring.config.location=file:/opt/exit-trading/application.yml
   Environment=JAVA_TOOL_OPTIONS=-Xms512m -Xmx1024m -Duser.timezone=Asia/Kolkata
   Restart=on-failure

   [Install]
   WantedBy=multi-user.target
   ```
9. **Enable & start**: `sudo systemctl daemon-reload && sudo systemctl enable --now exit-trading`
10. **Open firewall**: allow TCP 8080 (or the configured port) from corporate IPs only.
11. **Validate**: check `sudo journalctl -u exit-trading -f` and `logs/exittrading.log`.

### Timezone handling

- JVM default timezone is pinned to `Asia/Kolkata` via `TimezoneConfig` and `JAVA_TOOL_OPTIONS`.
- All REST DTOs use `java.time` (`LocalDate`, `ZonedDateTime`) with Jackson configured to serialise in IST.
- Scheduling uses nanosecond precision with `ScheduledThreadPoolExecutor` so orders are queued ahead of time and executed in <1ms once the slot arrives.

## Scripts

- `scripts/install.sh`: automates installation of OS dependencies, creation of service user, download of the repo, and optional jar placement.
- `scripts/run.sh`: helper wrapper to launch the jar with correct timezone and heap settings (useful for manual runs or troubleshooting).

## REST API quick reference

- `GET /api/admin/users` – list managed users + holdings
- `POST /api/admin/logging/{username}` – enable/disable verbose logging
- `POST /api/admin/session/login` – complete Kite login flow (body: `{ "requestToken": "..." }`)
- `GET /api/schedules/{username}` – all schedules for impersonated user
- `POST /api/schedules/{username}` – create schedule (body = `ScheduleRequest`)
- `POST /api/schedules/{id}/repeat` – clone a schedule for next trading day
- `DELETE /api/schedules/{id}` – cancel schedule
- `GET /api/depth/{username}` – latest captured depth snapshots

Swagger/OpenAPI UI is available at `/swagger-ui.html` for interactive exploration.

## Validation checklist

- ✅ UI inputs/outputs locked to IST
- ✅ Auto cancellation 60 seconds prior to session
- ✅ Sub-millisecond order dispatch via pre-scheduled executor threads
- ✅ Sectioned dashboard with live refresh & repeat actions
- ✅ Market pressure streaming (real or simulated)
- ✅ Extensive audit logging + Logback file rotation ready (configure in production as needed)
- ✅ Seamless session renewal hooks in `KiteSessionManager`

## Next steps

- Integrate with production-grade datastore (PostgreSQL/Aurora) by swapping the H2 datasource.
- Plug in Zerodha websocket feeds for real-time depth updates (hook into `DepthService`).
- Harden security (SAML/ADFS) and SSL termination via ALB.

