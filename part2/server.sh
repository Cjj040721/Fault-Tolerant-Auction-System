#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PID_DIR=".pids"
FE_MAIN="frontend.FrontEndServer"
REPLICA_MAIN="replica.ReplicaMain"

mkdir -p "$PID_DIR"

write_pid() {
  echo "$2" > "${PID_DIR}/$1.pid"
}

echo "Stopping old processes..."
pkill -x rmiregistry           || true
pkill -f 'frontend\.FrontEndServer' || true
pkill -f 'replica\.ReplicaMain'    || true
if [ -d "$PID_DIR" ]; then
  for f in "$PID_DIR"/*.pid; do
    [ -f "$f" ] || continue
    pid=$(cat "$f")
    kill "$pid" 2>/dev/null || true
    rm -f "$f"
  done
fi
sleep 1

echo "Building project..."
# protobuf-maven-plugin:0.6.1 extracts files with macOS extended attributes that
# Java's File.delete() cannot remove, causing silent proto code-gen failures.
rm -rf target/protoc-dependencies/ 2>/dev/null || true
mvn -q clean package

START_TIME=$SECONDS

echo "Starting rmiregistry..."
rmiregistry -J-Djava.class.path=target/classes &
write_pid "rmiregistry" $!
sleep 1

echo "Starting FrontEnd..."
( mvn -q exec:java -Dexec.mainClass="${FE_MAIN}" ) &
write_pid "frontend" $!
sleep 2

echo "Starting Replica 1 (will become sequencer)..."
( mvn -q exec:java -Dexec.mainClass="${REPLICA_MAIN}" -Dexec.args="1" ) &
write_pid "replica1" $!
sleep 2

echo "Starting Replica 2..."
( mvn -q exec:java -Dexec.mainClass="${REPLICA_MAIN}" -Dexec.args="2" ) &
write_pid "replica2" $!
sleep 1

echo "Starting Replica 3..."
( mvn -q exec:java -Dexec.mainClass="${REPLICA_MAIN}" -Dexec.args="3" ) &
write_pid "replica3" $!

echo "Starting Replica 4..."
( mvn -q exec:java -Dexec.mainClass="${REPLICA_MAIN}" -Dexec.args="4" ) &
write_pid "replica4" $!

echo "Starting Replica 5..."
( mvn -q exec:java -Dexec.mainClass="${REPLICA_MAIN}" -Dexec.args="5" ) &
write_pid "replica5" $!


ELAPSED=$((SECONDS - START_TIME))
echo "System ready in ${ELAPSED} seconds. FrontEnd on gRPC :50055, RMI registry on :1099"
echo "PID files in ${PID_DIR}/"
