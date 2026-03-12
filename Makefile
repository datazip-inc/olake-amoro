#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026

COMPOSE_DIR := docker/kind
KIND_CLUSTER := fusion-cluster
AMORO_DIST_TAR := $(CURDIR)/dist/target/apache-amoro-0.9-SNAPSHOT-bin.tar.gz
AMORO_RUNTIME_HOME := $(CURDIR)/dist/target/amoro-0.9-SNAPSHOT
AMORO_BIN_HOME := $(CURDIR)/dist/src/main/amoro-bin

.PHONY: start-fusion-docker clean-fusion-docker start-deps stop-deps prepare-optimizer-lib prepare-debug-runtime setup-debug-mode clean-debug-mode sync-frontend spotless-fix help

# Default target
.DEFAULT_GOAL := help

help:
	@echo "Fusion + Kind (Spark on Kubernetes)"
	@echo ""
	@echo "Usage:"
	@echo "  make start-fusion-docker   Start everything (Kind cluster + all services + optimizer) *Before running make sure you have installed KIND*"
	@echo "  make clean-fusion-docker   Remove everything (Kind cluster + services + volumes)"
	@echo "  make start-deps            Start Postgres and Minio for IDE debugging"
	@echo "  make stop-deps             Stop Postgres and Minio"
	@echo "  make setup-debug-mode      deps + build + install to ~/.m2 + lib sync"
	@echo "  make clean-debug-mode      Stop deps + cleanup extracted runtime"
	@echo "  make sync-frontend         Sync built frontend assets to target/ (fixes blank UI without rebuild)"
	@echo "  make spotless-fix          Auto-fix all Spotless (Google Java Format) violations"
	@echo ""
	@echo "Access:"
	@echo "  Fusion Web UI : http://localhost:1630  (admin / password)"
	@echo "  MinIO Console : http://localhost:9001  (admin / password)"
	@echo ""

start-fusion-docker:
	@echo "Starting Fusion (Kind cluster + all services)..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile prod up -d
	@echo ""
	@echo "Exporting Kind kubeconfig to host..."
	@kind export kubeconfig --name $(KIND_CLUSTER) 2>/dev/null

clean-fusion-docker:
	@echo "Removing Kind clusters..."
	@kind delete cluster --name $(KIND_CLUSTER) 2>/dev/null 
	@kind delete cluster --name fusion-spark-cluster 2>/dev/null
	@echo "Removing Docker services and volumes..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile prod down -v
	@echo "Teardown complete."

start-deps:
	@echo "Starting local dependencies (Postgres & Minio)..."
	@docker compose -f docker/kind/docker-compose.yml --profile dev up -d

stop-deps:
	@echo "Stopping local dependencies (Postgres & Minio)..."
	@docker compose -f docker/kind/docker-compose.yml --profile dev down

prepare-debug-runtime:
	@echo "Cleaning up stale optimizer logs (prevents RAT license check failure)..."
	@rm -rf "$(AMORO_BIN_HOME)/logs/optimizer-local-test-"*
	@echo "Removing all target directories to prevent stale/corrupt class files..."
	@find "$(CURDIR)" -maxdepth 3 -name target -type d -exec rm -rf {} + 2>/dev/null; true
	@echo "Building and installing all modules to local Maven repo (~/.m2)..."
	@./mvnw clean install -DskipTests -Drat.skip=true -Dspotless.skip=true -Dcheckstyle.skip=true -B -ntp
	@$(MAKE) prepare-optimizer-lib

prepare-optimizer-lib:
	@if [ ! -f "$(AMORO_DIST_TAR)" ]; then \
		echo "Missing distribution tar: $(AMORO_DIST_TAR)"; \
		echo "Build it first with: mvn -DskipTests package"; \
		exit 1; \
	fi
	@mkdir -p "$(CURDIR)/dist/target"
	@rm -rf "$(AMORO_RUNTIME_HOME)"
	@tar -xzf "$(AMORO_DIST_TAR)" -C "$(CURDIR)/dist/target"
	@rm -rf "$(AMORO_BIN_HOME)/lib"
	@cp -R "$(AMORO_RUNTIME_HOME)/lib" "$(AMORO_BIN_HOME)/lib"
	@echo "Synced optimizer libs to: $(AMORO_BIN_HOME)"

setup-debug-mode:
	@echo "Setting up debug mode (deps + build + install to ~/.m2 + lib sync)..."
	@$(MAKE) start-deps
	@$(MAKE) prepare-debug-runtime
	@echo ""
	@echo "Setup complete."
	@echo "Next: reload VS Code Java project, then run 'AmoroServiceContainer' from launch.json. Follow .vscode/debug.md"

clean-debug-mode:
	@echo "Tearing down debug mode (deps + extracted runtime cleanup)..."
	@$(MAKE) stop-deps
	@rm -rf "$(AMORO_RUNTIME_HOME)"
	@echo "Teardown complete."

spotless-fix:
	@echo "Running Spotless auto-fix (Google Java Format + import ordering)..."
	@./mvnw spotless:apply -B -ntp
	@echo "Spotless fix complete."

sync-frontend:
	@echo "Syncing frontend assets from src/main/resources/static → target/classes/static ..."
	@SRC=amoro-web/src/main/resources/static; \
	DST=amoro-web/target/classes/static; \
	if [ ! -d "$$SRC" ]; then \
		echo "ERROR: $$SRC not found. Run 'pnpm build' inside amoro-web/ first."; \
		exit 1; \
	fi; \
	mkdir -p "$$DST"; \
	cp -r "$$SRC"/. "$$DST"/
	@echo "Done. Refresh http://localhost:1630 in your browser."
