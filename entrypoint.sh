#!/bin/sh
set -e

# Render's managed Postgres exposes host/port/database/user/password as separate env vars
# (see render.yaml) rather than the single JDBC URL Spring expects. Translate them here so
# docker-compose (which already sets DB_URL/DB_USERNAME/DB_PASSWORD directly) keeps working
# unchanged.
if [ -n "$PGHOST" ]; then
  export DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE}"
  export DB_USERNAME="$PGUSER"
  export DB_PASSWORD="$PGPASSWORD"
fi

exec java -jar app.jar
