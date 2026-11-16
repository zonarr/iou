-- iou baseline schema.
--
-- Money columns: NUMERIC(14,2) (two decimal places, exact).
-- Every money-carrying row stores the amount as entered (original_amount + currency),
-- the manually entered exchange rate to THB, and the converted thb_amount.
-- Balances and settle-up only ever read thb_amount.

-- A row is either a real account (email + password) or a placeholder: a name added to a
-- group for somebody who has no account yet. Debts and payments point at users.id either
-- way, so claiming a placeholder is "re-point its rows at the real account".
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254),
    password_hash VARCHAR(100),
    display_name  VARCHAR(80)  NOT NULL,
    placeholder   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by    BIGINT       REFERENCES users (id),
    -- Set when a placeholder has been claimed; the row is kept so history still resolves.
    merged_into   BIGINT       REFERENCES users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_account_or_placeholder CHECK (
        (placeholder AND email IS NULL AND password_hash IS NULL)
        OR (NOT placeholder AND email IS NOT NULL AND password_hash IS NOT NULL)
    )
);
CREATE UNIQUE INDEX users_email_key ON users (email);

CREATE TABLE groups (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(80) NOT NULL,
    owner_id     BIGINT      NOT NULL REFERENCES users (id),
    invite_code  VARCHAR(40) NOT NULL UNIQUE,
    -- "Trusted group": debts and payments recorded by any member count immediately.
    auto_confirm BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id  BIGINT      NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    user_id   BIGINT      NOT NULL REFERENCES users (id),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT group_members_unique UNIQUE (group_id, user_id)
);
CREATE INDEX group_members_user_idx ON group_members (user_id);

CREATE TABLE expenses (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT         REFERENCES groups (id),
    description     VARCHAR(200)   NOT NULL,
    original_amount NUMERIC(14, 2) NOT NULL CHECK (original_amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    exchange_rate   NUMERIC(18, 8) NOT NULL CHECK (exchange_rate > 0),
    thb_amount      NUMERIC(14, 2) NOT NULL CHECK (thb_amount >= 0),
    split_type      VARCHAR(10)    NOT NULL CHECK (split_type IN ('EQUAL', 'EXACT', 'PERCENT', 'SHARES')),
    spent_on        DATE           NOT NULL,
    created_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX expenses_group_idx ON expenses (group_id);

-- Who paid, and how much each (one row for the usual single payer).
CREATE TABLE expense_payers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expense_id      BIGINT         NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    user_id         BIGINT         NOT NULL REFERENCES users (id),
    original_amount NUMERIC(14, 2) NOT NULL CHECK (original_amount > 0),
    thb_amount      NUMERIC(14, 2) NOT NULL,
    CONSTRAINT expense_payers_unique UNIQUE (expense_id, user_id)
);
CREATE INDEX expense_payers_user_idx ON expense_payers (user_id);

-- Who the expense was for, and each person's share. weight keeps the number the user typed
-- for PERCENT and SHARES splits so the expense can be edited later.
CREATE TABLE expense_shares (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expense_id      BIGINT         NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    user_id         BIGINT         NOT NULL REFERENCES users (id),
    original_amount NUMERIC(14, 2) NOT NULL CHECK (original_amount >= 0),
    thb_amount      NUMERIC(14, 2) NOT NULL,
    weight          NUMERIC(14, 4),
    CONSTRAINT expense_shares_unique UNIQUE (expense_id, user_id)
);
CREATE INDEX expense_shares_user_idx ON expense_shares (user_id);

-- A debt is either a standalone IOU (expense_id IS NULL) or generated from an expense.
CREATE TABLE debts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT         REFERENCES groups (id),
    expense_id      BIGINT         REFERENCES expenses (id),
    debtor_id       BIGINT         NOT NULL REFERENCES users (id),
    creditor_id     BIGINT         NOT NULL REFERENCES users (id),
    original_amount NUMERIC(14, 2) NOT NULL CHECK (original_amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    exchange_rate   NUMERIC(18, 8) NOT NULL CHECK (exchange_rate > 0),
    thb_amount      NUMERIC(14, 2) NOT NULL CHECK (thb_amount >= 0),
    description     VARCHAR(200)   NOT NULL,
    status          VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    created_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT debts_two_people CHECK (debtor_id <> creditor_id)
);
CREATE INDEX debts_group_idx ON debts (group_id);
CREATE INDEX debts_expense_idx ON debts (expense_id);
CREATE INDEX debts_debtor_idx ON debts (debtor_id);
CREATE INDEX debts_creditor_idx ON debts (creditor_id);

CREATE TABLE payments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT         REFERENCES groups (id),
    from_user_id    BIGINT         NOT NULL REFERENCES users (id),
    to_user_id      BIGINT         NOT NULL REFERENCES users (id),
    original_amount NUMERIC(14, 2) NOT NULL CHECK (original_amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    exchange_rate   NUMERIC(18, 8) NOT NULL CHECK (exchange_rate > 0),
    thb_amount      NUMERIC(14, 2) NOT NULL CHECK (thb_amount >= 0),
    note            VARCHAR(200),
    status          VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    created_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT payments_two_people CHECK (from_user_id <> to_user_id)
);
CREATE INDEX payments_group_idx ON payments (group_id);
CREATE INDEX payments_from_idx ON payments (from_user_id);
CREATE INDEX payments_to_idx ON payments (to_user_id);

-- Append-only history of every change. involved_user_ids lets a person see entries for
-- items that are not in any group (one-on-one debts and payments).
CREATE TABLE audit_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type       VARCHAR(20) NOT NULL,
    entity_id         BIGINT      NOT NULL,
    action            VARCHAR(20) NOT NULL,
    group_id          BIGINT      REFERENCES groups (id) ON DELETE CASCADE,
    actor_id          BIGINT      NOT NULL REFERENCES users (id),
    involved_user_ids BIGINT[]    NOT NULL DEFAULT '{}',
    before_state      JSONB,
    after_state       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_group_idx ON audit_log (group_id, id DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);
CREATE INDEX audit_log_involved_idx ON audit_log USING GIN (involved_user_ids);
