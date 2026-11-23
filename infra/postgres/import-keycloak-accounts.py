"""Import password-only accounts from the former local Keycloak database."""

import argparse
import base64
import csv
import io
import json
import subprocess
import sys
import uuid


EXPORT = """
SELECT coalesce(json_agg(a), '[]'::json)
FROM (
    SELECT u.id, u.username, u.email, u.enabled,
           c.credential_data::jsonb AS parameters, c.secret_data::jsonb AS secret,
           NOT EXISTS (SELECT FROM credential other WHERE other.user_id = u.id AND other.type <> 'password')
               AND NOT EXISTS (SELECT FROM user_required_action action WHERE action.user_id = u.id) AS password_only,
           CASE
               WHEN 'admin' = ANY(grants.roles) THEN 'admin'
               WHEN 'teacher' = ANY(grants.roles) THEN 'teacher'
               ELSE 'student'
           END AS role
    FROM user_entity u
    JOIN realm r ON r.id = u.realm_id
    LEFT JOIN LATERAL (
        SELECT credential_data, secret_data
        FROM credential
        WHERE user_id = u.id AND type = 'password'
        ORDER BY priority, created_date DESC
        LIMIT 1
    ) c ON true
    CROSS JOIN LATERAL (
        SELECT array_agg(kr.name) AS roles
        FROM user_role_mapping ur
        JOIN keycloak_role kr ON kr.id = ur.role_id AND kr.realm_id = r.id
        WHERE ur.user_id = u.id
    ) grants
    WHERE r.name = 'kodr' AND u.service_account_client_link IS NULL AND u.federation_link IS NULL
) a;
"""

BEGIN_IMPORT = """
BEGIN;
CREATE TEMP TABLE legacy_accounts (
    id uuid, username text, email text, password_hash text, enabled boolean, role text
);
COPY legacy_accounts (id, username, email, password_hash, enabled, role) FROM STDIN WITH (FORMAT csv);
"""

END_IMPORT = """
DO $$
BEGIN
    IF EXISTS (
        SELECT FROM legacy_accounts l JOIN auth_accounts a ON
            a.id = l.id OR a.username = l.username OR a.email = l.email
        WHERE (a.id, a.username, a.email) IS DISTINCT FROM (l.id, l.username, l.email)
    ) THEN
        RAISE EXCEPTION 'Existing local accounts conflict with the import';
    END IF;
END $$;

INSERT INTO auth_accounts (id, username, email, password_hash, enabled, role)
SELECT id, username, email, password_hash, enabled, role FROM legacy_accounts
ON CONFLICT (id) DO NOTHING;
COMMIT;
"""


def query(command, database, sql):
    result = subprocess.run(command + ["-d", database], input=sql, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"PostgreSQL rejected the operation on {database}. Check the database and account conflicts.")
    return result.stdout


def convert(account):
    username = (account["username"] or "").strip().lower()
    email = (account["email"] or "").strip().lower()
    role = account["role"]
    if not (account["password_only"] and username and email and len(email) <= 254):
        raise ValueError("Account requires manual migration")

    parameters = account["parameters"]
    if not parameters or parameters.get("algorithm") != "argon2":
        raise ValueError("Unsupported password hash")
    extra = parameters["additionalParameters"]
    if extra["type"] != ["id"] or extra["version"] != ["1.3"]:
        raise ValueError("Unsupported Argon2 variant")
    memory = int(extra["memory"][0])
    iterations = int(parameters["hashIterations"])
    parallelism = int(extra["parallelism"][0])
    if not (8 * parallelism <= memory <= 131072 and 1 <= parallelism <= 16 and 1 <= iterations <= 100):
        raise ValueError("Argon2 parameters are outside the import range")

    salt, digest = (account["secret"][name] for name in ("salt", "value"))
    if len(base64.b64decode(salt, validate=True)) < 8 or len(base64.b64decode(digest, validate=True)) < 16:
        raise ValueError("Malformed Argon2 hash")

    encoded = f"$argon2id$v=19$m={memory},t={iterations},p={parallelism}${salt.rstrip('=')}${digest.rstrip('=')}"
    return uuid.UUID(account["id"]), username, email, encoded, account["enabled"], role


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", help="Use local psql and its PG environment variables")
    parser.add_argument("--source-db", default="kodr_keycloak")
    parser.add_argument("--target-db", default="kodr_auth")
    args = parser.parse_args()
    command = ["psql"] if args.local else ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres"]
    command += ["-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1"]

    accounts = json.loads(query(command, args.source_db, EXPORT))
    rows = io.StringIO()
    writer = csv.writer(rows, lineterminator="\n")
    skipped = 0
    for account in accounts:
        try:
            writer.writerow(convert(account))
        except (TypeError, ValueError, KeyError, IndexError):
            skipped += 1
    if len(accounts) > skipped:
        query(command, args.target_db, BEGIN_IMPORT + rows.getvalue() + "\\.\n" + END_IMPORT)
    print(f"Supported accounts processed: {len(accounts) - skipped}. Skipped: {skipped}.")
    return bool(skipped)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
