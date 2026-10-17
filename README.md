# sec-mcp-server

An [MCP](https://modelcontextprotocol.io) server, written in Java 21 with Spring AI, that lets LLM clients such as Claude Desktop research US public companies using **SEC EDGAR** filings.

Ask *"What are the main risk factors in Apple's latest 10-K?"* and the model will:

1. call `search_company` to resolve "Apple" to its CIK and recent filings, then
2. call `get_filing_section` to read Item 1A, page by page.

> Status: Phase 1 (search + section reading over stdio) is complete. XBRL financials, a Postgres cache, filing diffs, HTTP deployment and an evaluation harness are planned.

## Tools

| Tool | What it does |
|---|---|
| `search_company` | Resolves a ticker (`AAPL`, `BRK.B`), company name (`Apple`) or CIK (`320193`) to the company and its 10 most recent 10-K/10-Q/8-K filings. The optional `form` argument filters to one form type. |
| `get_filing_section` | Returns **Risk Factors** or **MD&A** from a 10-K or 10-Q, identified by accession number or by ticker + form + fiscal year. The text is paged with `offset`/`limit`, and each page includes `has_more` and the filing it came from. |
| `ping` | Connectivity check; does not contact the SEC. |

<details>
<summary>Example: <code>get_filing_section(ticker="AAPL", section="RISK_FACTORS")</code></summary>

```json
{
  "section": "RISK_FACTORS",
  "filing": {
    "cik": "0000320193", "company": "Apple Inc.", "ticker": "AAPL", "form": "10-K",
    "filing_date": "2025-10-31", "period_of_report": "2025-09-27",
    "accession_number": "0000320193-25-000079"
  },
  "text": "Item 1A. Risk Factors\n\nThe following summarizes factors that could ...",
  "offset": 0,
  "returned_chars": 10000,
  "total_chars": 67757,
  "has_more": true
}
```
</details>

## Requirements

- JDK 21 or newer (developed and tested on JDK 25, compiled for Java 21)
- No Maven install needed: use the included wrapper `./mvnw`
- Node.js 22.19+ (only for MCP Inspector)

## Build

```bash
./mvnw verify
```

This runs all tests (none of them touch the network) and builds `target/sec-mcp-server.jar`.

## Configure your SEC User-Agent

The SEC [requires](https://www.sec.gov/os/accessing-edgar-data) every request to identify the caller as `"Your Name your@email.com"`, and blocks anonymous clients. The server refuses to start without it. Never commit it.

Pick one:

- **Local file (recommended for development):** copy `config/application.yml.example` to `config/application.yml`, which is gitignored, and fill it in. Spring Boot loads it automatically when the server runs from the project folder.
- **Environment variable:** `EDGAR_USER_AGENT="Your Name your@email.com"`.

## Use with Claude Desktop

Claude Desktop starts the server as a subprocess and talks to it over stdio. Add an entry to `claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`; Windows: `%APPDATA%\Claude\claude_desktop_config.json`), then restart Claude Desktop.

```json
{
  "mcpServers": {
    "sec-filings": {
      "command": "/absolute/path/to/java",
      "args": [
        "-jar", "/absolute/path/to/sec-mcp-server/target/sec-mcp-server.jar",
        "--spring.config.additional-location=optional:file:/absolute/path/to/sec-mcp-server/config/"
      ]
    }
  }
}
```

- **`command`** must be an absolute path, because Claude Desktop does not use your shell's `PATH`. On macOS, `/usr/libexec/java_home -v 21+` prints the JDK home; append `/bin/java`.
- **`--spring.config.additional-location`** points at the gitignored `config/` folder, so your User-Agent lives in one place. Claude Desktop launches the server from a different working directory, so this file is not found automatically. Alternatively, drop that argument and add `"env": { "EDGAR_USER_AGENT": "Your Name your@email.com" }`.
- **Logs** go to `~/.sec-mcp-server/logs/server.log` (override with `SEC_MCP_LOG_FILE`). Warnings and errors also go to stderr, which Claude Desktop shows in its MCP log (`~/Library/Logs/Claude/mcp-server-sec-filings.log` on macOS). Nothing except protocol messages is ever written to stdout.

## Try it with MCP Inspector

```bash
npx @modelcontextprotocol/inspector java -jar target/sec-mcp-server.jar
```

Or, without the UI:

```bash
npx @modelcontextprotocol/inspector --cli java -jar target/sec-mcp-server.jar --method tools/call --tool-name search_company --tool-arg query=Apple --tool-arg form=10-K
```

## Run over HTTP

```bash
java -jar target/sec-mcp-server.jar --spring.profiles.active=http
```

This serves Streamable HTTP at `http://localhost:8080/mcp`. There is **no authentication yet**, so do not expose it beyond localhost.

## Design notes

- **SEC fair access.** Every request goes through one `EdgarClient`. It sends the User-Agent, waits for a token from a non-blocking token bucket (8 req/s with capacity 1, so no one-second window can exceed the SEC's 10 req/s), and retries 429/5xx/network errors with jittered exponential backoff. Each retry waits for its own token.
- **No arbitrary URLs.** SEC URLs are built only from validated identifiers: CIK, accession number, and a document name taken from SEC data. Tool callers never supply a URL.
- **Ticker index.** `company_tickers.json` (about 10k companies) is loaded lazily, shared by concurrent first callers, and refreshed daily. If a refresh fails, the server keeps serving the previous index.
- **Section extraction and the table-of-contents trap.** Item headings appear in the table of contents, in running page headers and in cross-references. Each heading that starts a line is a candidate. A candidate ends at the next heading of *any other* Item, and the longest candidate wins. Tests run against real Apple 10-K/10-Q and Microsoft 10-K filings plus a synthetic filing with a messy table of contents.
- **Fitting 200-page filings into context.** HTML is cleaned to text: hidden inline-XBRL, page numbers and running headers/footers are removed. Apple's 1.5 MB 10-K becomes about 200 KB of text. Sections are then served in pages of up to 20,000 characters with `has_more`, so the model reads only as much as it needs.
- **Agent-filed filings.** An accession number's prefix is the CIK of whoever *submitted* the filing. That is often a filing agent rather than the company, so `get_filing_section` also accepts the ticker.
- **Errors the model can act on.** Bad input and SEC failures become tool results with `isError: true` and messages such as *"No company found for 'X'. Try the ticker symbol…"*. Unexpected exceptions are logged and replaced with a generic message.

## Tests

```bash
./mvnw test                                                # unit + integration, offline
./mvnw test -Dtest=EdgarLiveSmokeTest -Dedgar.live=true    # 5 real SEC requests
```

The integration test starts the server, connects with the official MCP Java client over Streamable HTTP, and calls every tool against WireMock serving saved real EDGAR responses.

## Project layout

```
src/main/java/io/github/sumukhesh/secmcp
├── tools/     MCP tool definitions (thin; delegate to services)
├── edgar/     EdgarClient, rate limiter, ticker/CIK resolver, company search
├── parsing/   HTML-to-text, section extraction, paginated section service
├── cache/     (Phase 2) Postgres read-through cache
├── model/     Tool output records
└── config/    Configuration properties
```

## Tech stack

Java 21 · Spring Boot 4.1 · Spring AI 2.0 MCP server (WebFlux) · MCP Java SDK 2.0 · Reactor · jsoup · JUnit 5 · WireMock · AssertJ
