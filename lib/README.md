# Local Optional Libraries

This directory is ignored by Git except for this file. Use it to place optional, locally-provided libraries that are not distributed with the repository.

Primary use case here: add Zerodha's Kite Connect SDK jar for live trading.

What to do
- Download the official `kiteconnect.jar` from Zerodha.
- Place it as `lib/kiteconnect.jar`.
- Configure your API credentials in `src/main/resources/application.yml` (e.g., `kite.apiKey`, `kite.apiSecret`).

Recommended: install jar into local Maven repo and depend on it
- Install locally (no internet needed):
  `mvn install:install-file -Dfile=lib/kiteconnect.jar -DgroupId=com.zerodhatech -DartifactId=kiteconnect -Dversion=local -Dpackaging=jar`
- Then add to pom.xml:
  ```xml
  <dependency>
    <groupId>com.zerodhatech</groupId>
    <artifactId>kiteconnect</artifactId>
    <version>local</version>
  </dependency>
  ```
- After that, `mvn spring-boot:run` and packaged jars will include the SDK.

Alternative: run packaged jar with external lib via loader.path
- Build: `mvn clean package`
- Run:
  - Windows (PowerShell):
    `java -Dloader.path=lib/ -Dloader.main=com.exittrading.app.ExitTradingApplication -jar target/exit-trading-1.0.0.jar org.springframework.boot.loader.PropertiesLauncher`
  - Linux/macOS:
    `java -Dloader.path=lib/ -Dloader.main=com.exittrading.app.ExitTradingApplication -jar target/exit-trading-1.0.0.jar org.springframework.boot.loader.PropertiesLauncher`

OAuth Redirect URL setup
- Set Redirect URL in Kite developer console to: `http://localhost:8080/api/admin/session/callback` (or your host)
- Flow: log in via `https://kite.zerodha.com/connect/login?api_key=YOUR_API_KEY&v=3`; on success, the app exchanges `request_token` and redirects to `/`.


# What to do now?

Verify application.yml uses your real Kite developer app credentials:
kite.apiKey: must equal the app key used in the browser login URL.
kite.apiSecret: must be the secret for that same app.
Start a fresh login:
Use your existing link (since you’re set up that way): https://kite.zerodha.com/connect/login?api_key=YOUR_API_KEY&v=3
After login, you’ll be redirected to /external/kiteredirect; the app will auto-exchange the token and send you back to /.
If you want to trigger manually via UI, paste a fresh request_token immediately into the “Kite Session” box and click “Connect Kite”.
Notes
- When the jar is absent, the app automatically uses paper trading mode.
- This folder is ignored in `.gitignore`; only this README is committed.


 
# Here’s the exact flow to get a Kite request_token and use it with the app.

## Where To Login

Open this URL in your browser: https://kite.zerodha.com/connect/login?api_key=atwxtuh4sugm4hvy&v=3
Log in with your Zerodha credentials and complete 2FA.
After login, Kite redirects to your app’s “Redirect URL” (configured in the Kite developer console). The redirected URL will look like: http://<your-redirect-host>/?request_token=XXXXX&status=success
Copy the request_token value from the address bar.
Tip: In the Kite developer console, set the “Redirect URL” to something you can access locally during dev (e.g., http://localhost:8080/), otherwise the login will fail with “Invalid redirect URL”.

## Where To Paste It

Open the app dashboard at http://localhost:8080.
In the “Kite Session” section, paste the request_token and click “Connect Kite”.
The session badge turns green on success, and your Kite user appears in the “Impersonate User” dropdown.
Notes:

request_token is one-time and short-lived. If you reuse it or wait too long, the exchange fails.
The app requires the Kite SDK jar on classpath; if missing you’ll get an error asking to place kiteconnect.jar in lib/ and restart.

## Why apiKey and apiSecret Are In application.yml

They power the server-side OAuth exchange:
The backend constructs the SDK with your API key and exchanges the request_token for an access_token using your API secret.
In this project, that’s done in SessionController.login(...) via generateSession(requestToken, apiSecret) and then stored in KiteSessionManager for subsequent API calls.
Summary:
kite.apiKey identifies your Kite developer app and is used in both the login URL and SDK initialization.
kite.apiSecret is used only on the server to securely exchange the request_token for an access_token. It should never be exposed to the browser.
For production, prefer environment variables or a secrets manager instead of committing secrets in application.yml.
