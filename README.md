# PayCore

A simulated payment gateway (a mini Stripe/Razorpay) built for education and as a portfolio project:
merchants get API keys, create payments through a hosted checkout, and receive signed webhooks. Every money
movement is recorded in a double-entry ledger. A fake bank simulator stands in for real card networks.

> **PayCore never processes real card data.** Only the documented test card numbers are accepted; anything else is
> rejected before it reaches any storage or log. Do not enter real card numbers anywhere in this project.

Status: under construction — see [PROGRESS.md](PROGRESS.md). The full README (architecture, design decisions,
live demo links, measured results) lands in the final phase.

## Local setup (API)

Requirements: JDK 21, Docker Desktop.

```bash
docker compose up -d postgres redis
cd api
./mvnw verify            # Windows: .\mvnw.cmd verify
```

Run the API against the compose services with the variables from [.env.example](.env.example), or run everything in
containers with `docker compose --profile full up --build` and open http://localhost:8080/swagger-ui.
