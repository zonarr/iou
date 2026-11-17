# Deploying iou

This puts iou on the internet for free, so your family can use it from their phones:

- **Render** runs the app (API and web app together, from the `Dockerfile`).
- **Neon** holds the PostgreSQL database.

It takes about 20 minutes. You need a GitHub account (you have one) and a web browser.
Nothing here needs a credit card.

> I could not open render.com or neon.com while writing this, so a button label may read
> slightly differently from what is written below. The order of the steps and the values
> to type are what matter.

## What it costs and what the limits are

| | Free allowance | What that means for you |
|---|---|---|
| Render web service | 512 MB RAM, 0.1 CPU. **Sleeps after 15 minutes with no visitors.** | The first person to open the app after a quiet spell waits about a minute. The app shows "Waking up the server…" while it waits. |
| Neon database | About 0.5–1 GB storage, 100 compute-hours a month, sleeps after 5 idle minutes. | A family trip uses a few megabytes. The database wakes in about a second. |

Before the trip, look at [Render's pricing page](https://render.com/pricing) and
[Neon's pricing page](https://neon.com/pricing) once, in case these limits have changed.

If the wake-up wait annoys people during the trip, see
[Making it faster](#making-it-faster-optional). You can change that at any time without
losing data.

## Step 0: get the code onto `main`

Render deploys from one branch. The work is on the branch `claude/happy-planck-3j9e24`.
Either merge it into `main` on GitHub (open a pull request and merge it), or pick that
branch when Render asks which branch to deploy.

## Step 1: create the database on Neon

1. Go to <https://neon.com> and sign up (signing in with GitHub is the quickest).
2. Create a project:
   - **Name:** `iou`
   - **Postgres version:** `16` (the version the tests run against; 17 also works)
   - **Region:** `AWS Europe (Frankfurt)`. Keep the database and the app in the same
     region. `render.yaml` puts the app in Frankfurt.
3. On the project page press **Connect**.
4. Turn **Connection pooling off**. The address shown must **not** contain `-pooler`.
   (The app keeps its own small pool, and the pooled address does not suit the
   start-up migrations.)
5. Copy the connection string. It looks like this:

   ```
   postgresql://neondb_owner:AbCd1234@ep-cool-name-123456.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
   ```

   Keep it private: it contains the database password. You paste it into Render in the
   next step, exactly as it is. You do not need to turn it into a `jdbc:` address.

## Step 2: create the app on Render

1. Go to <https://render.com> and sign up with GitHub.
2. Press **New +**, then **Blueprint**.
3. Connect your GitHub account if asked, and choose the repository **zonarr/iou**.
4. Render reads `render.yaml` from the repository and shows one service called `iou`.
5. It asks for one value, **`DATABASE_URL`**. Paste the Neon connection string from step 1.
6. Press **Apply** (or **Deploy Blueprint**).

Render now builds the Docker image and starts it. The first build takes 5 to 10 minutes.
Watch the **Logs** tab; it is finished when the service shows **Live**.

You do not need to set anything else:

| Variable | Set by | Purpose |
|---|---|---|
| `DATABASE_URL` | You, in step 2 | The Neon connection string |
| `IOU_JWT_SECRET` | Render, generated automatically | Signs login tokens. If it ever changes, everybody has to log in again. |
| `SPRING_PROFILES_ACTIVE` | `render.yaml` (`prod`) | Production settings |
| `IOU_JWT_TTL` | `render.yaml` (`P60D`) | A login lasts 60 days |
| `IOU_REGISTRATION_OPEN` | `render.yaml` (`true`) | Set to `false` later to stop new sign-ups |
| `PORT` | Render | The port the app listens on |

The database tables are created automatically the first time the app starts (Flyway).

## Step 3: check that it works

Render shows the address of your app at the top of the service page, something like
`https://iou-xxxx.onrender.com`. Open these in a browser:

| Address | You should see |
|---|---|
| `https://YOUR-APP.onrender.com/api/health` | `{"status":"UP"}` |
| `https://YOUR-APP.onrender.com/api/health/db` | `{"status":"UP","database":"UP"}` (the database connection works) |
| `https://YOUR-APP.onrender.com/` | The iou login screen |

If the second one fails, the `DATABASE_URL` is wrong: see [Troubleshooting](#troubleshooting).

## Step 4: set up the trip

Do this yourself first, on your own phone:

1. Open the app and **create your account**.
2. Press **New group** and name it (for example "Europe 2026").
3. On the **People** tab, add everybody by name ("Mum", "Dad", "Grandma"). They can be
   part of expenses straight away, before they have the app.
4. Still on **People**, tick **Trusted group**. Expenses then count immediately instead
   of waiting for each person to accept them.
5. Press **Share link** and send the invite link to the family chat.

Each family member then:

1. Opens the link and creates an account (name, email, password).
2. Taps **I am Mum** (their own name). Everything already recorded under that name moves
   to their account.
3. Adds the app to their home screen so it opens like an app:
   - **iPhone (Safari):** Share button, then **Add to Home Screen**.
   - **Android (Chrome):** menu (⋮), then **Add to Home screen** or **Install app**.

When everybody has joined, you can close the door behind them: in Render, open the
service, **Environment**, change `IOU_REGISTRATION_OPEN` to `false`, and save. The
service restarts and nobody new can sign up. If you think the invite link has leaked,
press **Make a new link** on the People tab.

## Making it faster (optional)

The free service sleeps after 15 quiet minutes. Three choices, all reversible:

- **Do nothing.** The first visit after a quiet spell takes about a minute.
- **Keep it awake for free.** Create a free monitor at a service such as UptimeRobot or
  cron-job.org that requests `https://YOUR-APP.onrender.com/api/health` every 10 minutes.
  That address does not touch the database, so Neon still sleeps and its compute hours are
  not used up. One always-on service fits in Render's free monthly hours, but check that on
  their pricing page.
- **Pay for the trip only.** In Render, change the service's plan from Free to Starter
  (about US$7 a month). It never sleeps and is several times faster. Change it back to
  Free when you are home.

## Day-to-day

**Updating the app.** Push to the deployed branch. Render rebuilds and redeploys by
itself. Database changes are applied automatically at start-up.

**Backing up.** Before the trip, and whenever you want a copy, run this on a computer
that has PostgreSQL's tools installed, using the Neon connection string:

```bash
pg_dump "postgresql://USER:PASSWORD@HOST/neondb?sslmode=require" --no-owner -f iou-backup.sql
```

Neon also keeps a short history of the database that you can restore from in its console
(look for **Restore** or **Branches**).

**Somebody forgot their password.** There is no "forgot password" email. Reset it by
hand: in the Neon console open the **SQL Editor** and run, with their email and a new
password of at least 8 characters:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE users
   SET password_hash = crypt('the-new-password', gen_salt('bf', 10))
 WHERE email = 'mum@example.com';
```

It should answer `UPDATE 1`. Emails are stored in lower case. Tell them the new password;
they can change it under **Me** in the app.

**Looking at the data.** The Neon SQL Editor works for that too, for example
`SELECT * FROM audit_log ORDER BY id DESC LIMIT 20;`.

## Troubleshooting

Open the service in Render and read the **Logs** tab first.

| What you see | Cause and fix |
|---|---|
| `IOU_JWT_SECRET must be set` | The secret was not generated. In **Environment**, add `IOU_JWT_SECRET` with any random text of 32 characters or more. |
| `DATABASE_URL must start with postgresql://` | Something other than the Neon connection string was pasted. Paste it again, with no quotes or spaces around it. |
| `password authentication failed` or `UnknownHostException` | The connection string is incomplete or out of date. Copy it again from Neon's **Connect** window. |
| Errors mentioning `prepared statement` or `advisory lock` | The pooled address (with `-pooler` in it) was used. Copy the string again with connection pooling switched off. |
| The service keeps restarting, logs end with `Out of memory` | Should not happen on 512 MB (the app was measured at about 300 MB). If it does, switch the plan to Starter. |
| "Waking up the server…" never goes away | Open `/api/health` directly. If that does not answer after three minutes, look at the Render logs: the last deploy probably failed. |
| Everybody was logged out | `IOU_JWT_SECRET` changed. Logging in again fixes it; no data is lost. |

## Running the production image on your own computer

To try exactly what Render runs, with Docker installed:

```bash
docker compose --profile app up --build
```

Then open <http://localhost:8080>. This starts PostgreSQL and the app together.

## What was and was not tested

- The Docker image is built and started against PostgreSQL in GitHub Actions on every
  push (the "Docker image starts" job), with Render's 512 MB memory limit.
- The app was run with the production settings and a `postgresql://` style
  `DATABASE_URL` against a local PostgreSQL 16.
- **Not tested:** an actual deployment on Render with a Neon database. I cannot create
  those accounts. Step 3 above is the test; please do it well before 16 December.
