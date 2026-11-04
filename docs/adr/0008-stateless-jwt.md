# ADR 0008: Stateless JWT for the analyst API

Status: accepted. Date: 2026-11-04.

## Context
Analysts need to log in, with at least two roles, and the dashboard is a single-page app talking to a JSON API. Other systems post transactions without a person.

## Decision
`POST /api/auth/login` checks a BCrypt password and returns an HS256 JWT (60 minutes) carrying the username and role, validated by Spring Security's resource server with no
server-side session. The reviewer on a review is read from the token, never from the request body. Ingestion uses a separate service API key (`X-API-Key`), compared in constant
time. Login is limited to 5 attempts a minute per IP, and unknown users and wrong passwords get the same answer. CSRF protection is off for `/api/**` because browsers do not send a bearer token by themselves.

## Consequences
- Simple to run and to scale: nothing to store or share between instances.
- A token cannot be revoked before it expires (60 minutes); there are no refresh tokens or "log out everywhere".
- The dashboard keeps the token in `sessionStorage`, which any script on the page could read (an XSS bug would expose it). An httpOnly cookie avoids that but brings CSRF back.
- One shared signing secret (`FRAUDGUARD_JWT_SECRET`); outside the local profile the app refuses to start without it.
- The login limit is in memory and uses the connection address, so behind a proxy all users would share one address.
- Roles are checked per route, with anything not listed defaulting to ADMIN-only.

## Alternatives considered
- **Server-side sessions.** Easy revocation, but shared state and CSRF handling.
- **An identity provider (Keycloak, OIDC).** The right choice for a real deployment; too heavy for a self-contained demo.
- **Login for ingestion too.** Rejected: services are not people and need no expiry-and-refresh dance.
