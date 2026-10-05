# iou

A backend service for tracking money owed between friends and family. It records who owes whom, and it works out the fewest payments needed to settle everyone up.

> **Status:** Early development. Backend only (REST API). No frontend yet.

## Features

### Debts and expenses
- **Simple IOU:** "Alice owes Bob ฿200", with one debtor and one creditor.
- **Equal split:** one person pays and the cost is split evenly among the selected members. For example, Alice pays ฿900 for dinner for 3 people, so Bob and Carol each owe her ฿300. Satang left over when the amount doesn't divide evenly is assigned deterministically, so totals always match.
- **Groups and one-on-one:** debts can be recorded inside a group (a trip, a household) or directly between two people with no group.

### Debt confirmation
Nobody should be able to put a debt on someone else's balance without their agreement, so:
- A debt recorded **by the debtor** ("I owe Bob ฿200") is confirmed immediately.
- A debt recorded **by anyone else** stays **pending** until the debtor accepts it. The debtor can accept or reject it.
- Pending debts are visible but are **not** counted in balances or settlement suggestions.
- A payment ("I paid Bob back ฿200") stays pending until the **creditor** confirms they received it.
- Edits and deletions are recorded in an audit log, and editing an amount sends the debt back to pending.
- Debts involving a placeholder member (see below) are confirmed automatically, since a placeholder can't respond. They switch to the normal flow once the person claims the profile.

### Placeholder members
You can add people to a group by name before they have an account. If they sign up later, they can **claim** the placeholder, and its full debt history moves to their account.

### Settle up (transaction minimization)
For any group, or for all of a user's balances, iou calculates a **suggested** payment plan with as few transfers as possible.

Suggestions are **advisory only**. They never change or delete recorded debts. Balances change only when users record and confirm actual payments.

**How it works:**
1. Calculate each person's **net balance** (total owed to them minus total they owe) from confirmed debts and payments.
2. Split people into creditors (positive balance) and debtors (negative balance).
3. Repeatedly match the largest debtor with the largest creditor, transfer the smaller of the two amounts, and repeat until every balance is zero.

This greedy approach needs at most *n − 1* payments for *n* people, and usually far fewer.

**Example:**
| Recorded debts | Net balances | Suggested plan |
|---|---|---|
| A owes B ฿100<br>B owes C ฿100<br>A owes C ฿50 | A: −150<br>B: 0<br>C: +150 | **A pays C ฿150** (1 payment instead of 3) |

> Finding the true minimum number of payments is NP-hard in general. The greedy method is the standard practical approach and gives optimal or near-optimal results for groups of realistic size.

### Currency
Thai Baht (฿, `THB`) only, for now. Amounts are stored as `BigDecimal` with 2 decimal places (satang) to avoid floating-point rounding errors.

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 (LTS) | Current long-term support release, with records and pattern matching |
| Framework | Spring Boot | Web, Data JPA, Validation, Security |
| Build | Maven | The most common Spring Boot build tool, with the most tutorials and examples |
| Database | PostgreSQL | Reliable transactions and `NUMERIC` type for exact money values. Free hosting is widely available |
| Migrations | Flyway | Versioned, repeatable schema changes |
| Auth | Spring Security + JWT | Stateless tokens that work with any future frontend or mobile app |

### Authentication (overview)
1. A user **registers** with email and password. The password is hashed with BCrypt and never stored in plain text.
2. The user **logs in** and receives a signed **JWT** (access token).
3. The client sends `Authorization: Bearer <token>` with every request.
4. The server checks the token signature on each request, so it doesn't need to store sessions.

## Domain model (draft)

```
User ──< GroupMember >── Group
             │
             │ (a member is either a real User or a placeholder name)
             ▼
Debt         : creditor, debtor, amount, description, group (optional),
               status [PENDING | CONFIRMED | REJECTED], createdBy, createdAt
Expense      : payer, total amount, participants → produces one Debt per participant
Payment      : from, to, amount, group (optional), status [PENDING | CONFIRMED | REJECTED]
AuditLog     : entity, action, changedBy, before/after, timestamp
```

## API (draft)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Create an account |
| `POST` | `/api/auth/login` | Get a JWT |
| `POST` | `/api/groups` | Create a group |
| `POST` | `/api/groups/{id}/members` | Add a member (user or placeholder) |
| `POST` | `/api/groups/{id}/debts` | Record a simple IOU in a group |
| `POST` | `/api/groups/{id}/expenses` | Record an equal-split expense |
| `POST` | `/api/debts` | Record a one-on-one IOU (no group) |
| `POST` | `/api/debts/{id}/accept` | Debtor confirms a pending debt |
| `POST` | `/api/debts/{id}/reject` | Debtor rejects a pending debt |
| `POST` | `/api/payments` | Record a repayment |
| `POST` | `/api/payments/{id}/confirm` | Creditor confirms a payment was received |
| `GET`  | `/api/groups/{id}/balances` | Net balance per member |
| `GET`  | `/api/groups/{id}/settle-up` | Suggested minimal payment plan for a group |
| `GET`  | `/api/me/settle-up` | Suggested plan across all of my balances |
| `POST` | `/api/placeholders/{id}/claim` | Link a placeholder to my account |

## Getting started

> Setup instructions will be added once the Spring Boot project is scaffolded.

Planned prerequisites: JDK 21, Maven (or the included `./mvnw` wrapper), and PostgreSQL 16+.

## Roadmap

### Core (in progress)
- [ ] Scaffold the Spring Boot project (Maven, Java 21)
- [ ] PostgreSQL + Flyway schema
- [ ] Register/login with JWT
- [ ] Groups and members, including placeholder members
- [ ] Simple IOUs and equal-split expenses
- [ ] Debt and payment confirmation flow
- [ ] Balance calculation
- [ ] Settle-up suggestion algorithm

### Potential features (ranked by recommended priority)
1. **Tests + CI:** JUnit tests, especially for the settle-up algorithm and split rounding, plus Testcontainers for real-Postgres integration tests, all running on GitHub Actions. This catches money-math bugs early.
2. **Docker Compose:** start PostgreSQL (and later the app) with one command. This makes local setup and deployment much easier.
3. **API docs (Swagger / springdoc-openapi):** interactive docs generated from the controllers, useful for testing and for a future frontend.
4. **Activity history:** a user-facing timeline of expenses, payments, and edits, built on the audit log.

### Later
- Custom splits (exact amounts, percentages, shares)
- Multiple payers per expense
- Multi-currency support
- Frontend (web and/or mobile)

## License

[MIT](LICENSE). Anyone can use, copy, or modify this code, including commercially, as long as the copyright notice is kept. It's the most common choice for personal and portfolio projects.
