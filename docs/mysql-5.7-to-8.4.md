# MySQL 5.7 to 8.4 upgrade

MySQL does not support attaching a MySQL 5.7 data directory directly to
MySQL 8.4. The supported in-place path is:

```text
MySQL 5.7 -> MySQL 8.0 -> MySQL 8.4 LTS
```

Do this on a maintenance window and test the procedure against a restored
backup before touching production.

## 1. Back up and check the current instance

Use the panel installer's backup action and copy both the SQL dump and the
current `.env` file away from the deployment directory.

Confirm that the dump is non-empty and can be restored into a temporary
MySQL 8.0 instance. Run MySQL Shell Upgrade Checker against the 5.7 instance
with MySQL 8.0 as the target, and resolve every reported incompatibility.

## 2. Upgrade from 5.7 to 8.0

Stop the panel and keep the existing `mysql_data` volume. Back up the current
`docker-compose.yml`, then download the matching IPv4 or IPv6 Compose file from
the same Flux Panel release as this guide. The new Compose file supports a
selectable MySQL image.

Set these values in `.env`:

```dotenv
MYSQL_IMAGE=mysql:8.0
MYSQL_VOLUME_NAME=mysql_data
```

Start only MySQL and wait for it to finish its automatic upgrade:

```bash
docker compose up -d mysql
docker compose logs -f mysql
```

After MySQL is healthy, run the Upgrade Checker again with MySQL 8.4 as the
target. Start the backend and frontend, then verify login, forwards, traffic
accounting, and a backup/restore cycle before continuing.

## 3. Upgrade from 8.0 to 8.4

Take another verified backup, stop the panel, then change `.env` to:

```dotenv
MYSQL_IMAGE=mysql:8.4
```

Start MySQL first, inspect its logs until the upgrade completes, and then
start the full stack:

```bash
docker compose up -d mysql
docker compose logs -f mysql
docker compose up -d
```

Keep both verified SQL dumps until the upgraded deployment has run through
normal production traffic and another successful restore test.

References:

- <https://dev.mysql.com/doc/refman/8.4/en/upgrade-paths.html>
- <https://dev.mysql.com/doc/refman/8.4/en/upgrade-prerequisites.html>
- <https://dev.mysql.com/doc/refman/8.4/en/upgrade-best-practices.html>
