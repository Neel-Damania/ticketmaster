CREATE TABLE IF NOT EXISTS shows (
  id             UUID PRIMARY KEY,
  name           TEXT NOT NULL,
  price_paise    BIGINT NOT NULL CHECK (price_paise >= 0),
  per_user_limit INT NOT NULL DEFAULT 4,
  total_seats    INT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seats (
  show_id         UUID NOT NULL REFERENCES shows(id),
  label           TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'available'
                  CHECK (status IN ('available','held','confirmed')),
  reservation_id  UUID,
  user_id         TEXT,
  hold_expires_at TIMESTAMPTZ,
  PRIMARY KEY (show_id, label)
);
CREATE INDEX IF NOT EXISTS seats_reservation_idx ON seats(reservation_id);
CREATE INDEX IF NOT EXISTS seats_user_idx ON seats(show_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS seats_hold_exp_idx ON seats(hold_expires_at) WHERE status = 'held';

CREATE TABLE IF NOT EXISTS reservations (
  id           UUID PRIMARY KEY,
  show_id      UUID NOT NULL REFERENCES shows(id),
  user_id      TEXT NOT NULL,
  seats        TEXT NOT NULL,
  amount_paise BIGINT NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('held','confirmed','cancelled','expired')),
  expires_at   TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS reservations_hold_idx ON reservations(expires_at) WHERE status = 'held';

CREATE TABLE IF NOT EXISTS user_show_quota (
  show_id UUID NOT NULL,
  user_id TEXT NOT NULL,
  PRIMARY KEY (show_id, user_id)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
  user_id        TEXT NOT NULL,
  idem_key       TEXT NOT NULL,
  request_hash   TEXT NOT NULL,
  reservation_id UUID,
  PRIMARY KEY (user_id, idem_key)
);
