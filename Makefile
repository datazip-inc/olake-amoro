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
KUBECTL := kubectl --context kind-$(KIND_CLUSTER)
AMORO_DEBUG_PORT ?= 5005
AMORO_DEBUG_SUSPEND ?= n
AMORO_DIST_TAR := $(CURDIR)/dist/target/apache-amoro-0.9-SNAPSHOT-bin.tar.gz
AMORO_RUNTIME_HOME := $(CURDIR)/dist/target/amoro-0.9-SNAPSHOT
AMORO_BIN_HOME := $(CURDIR)/dist/src/main/amoro-bin

.PHONY: setup-fusion stop-fusion restart-fusion debug-fusion teardown logs status pods shell alias help debug-local stop-local start-deps stop-deps ams-debug ams-start ams-dist prepare-optimizer-lib prepare-debug-runtime setup-debug-mode teardown-debug-mode sync-frontend

# Default target
.DEFAULT_GOAL := help

help:
	@echo "Fusion + Kind (Spark on Kubernetes)"
	@echo ""
	@echo "Usage:"
	@echo "  make setup-fusion   Start everything (Kind cluster + all services + optimizer)"
	@echo "  make stop-fusion    Stop Docker services (Kind cluster persists)"
	@echo "  make restart-fusion Restart Docker services"
	@echo "  make debug-fusion   Start with Java debugger on localhost:$(AMORO_DEBUG_PORT)"
	@echo "  make debug-local    Start Fusion with local optimizer, Postgres, Minio in debug mode"
	@echo "  make stop-local     Stop the local debug environment"
	@echo "  make start-deps     Start Postgres and Minio for IDE debugging"
	@echo "  make stop-deps      Stop Postgres and Minio"
	@echo "  make ams-start      Start AMS locally (no debugger)"
	@echo "  make ams-debug      Start AMS locally with JDWP on port 5005, then attach IDE"
	@echo "  make setup-debug-mode  One-command debug setup (deps + build + install to ~/.m2 + lib sync)"
	@echo "  make teardown-debug-mode  One-command debug teardown (stop deps + cleanup)"
	@echo "  make prepare-debug-runtime  Build+install all modules to ~/.m2, then sync optimizer lib"
	@echo "  make prepare-optimizer-lib  Extract dist tar and sync only lib/ to dist/src/main/amoro-bin"
	@echo "  make sync-frontend  Sync built frontend assets to target/ (fixes blank UI without rebuild)"
	@echo "  make teardown      Remove everything (Kind cluster + services + volumes)"
	@echo "  make logs          View Fusion logs"
	@echo "  make status        Show cluster and service status"
	@echo "  make pods          List Spark pods in Kubernetes"
	@echo "  make shell         Shell into Fusion container"
	@echo "  make alias         Set default namespace to spark"
	@echo ""
	@echo "Access:"
	@echo "  Fusion Web UI : http://localhost:1630  (admin / password)"
	@echo "  MinIO Console: http://localhost:9001  (admin / password)"
	@echo ""

setup-fusion:
	@echo "Starting Fusion (Kind cluster + all services)..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml up -d
	@echo ""
	@echo "Exporting Kind kubeconfig to host..."
	@kind export kubeconfig --name $(KIND_CLUSTER) 2>/dev/null
	@echo ""
	@echo "Follow progress:  make logs"
	@echo "Check status:     make status"

stop-fusion:
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml down

restart-fusion:
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml down
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml up -d
	@kind export kubeconfig --name $(KIND_CLUSTER) 2>/dev/null

debug-local:
	@echo "Starting Fusion (local optimizer, Postgres, Minio) with JDWP debugger on localhost:$(AMORO_DEBUG_PORT) (suspend=$(AMORO_DEBUG_SUSPEND))..."
	@AMORO_DEBUG_PORT=$(AMORO_DEBUG_PORT) AMORO_DEBUG_SUSPEND=$(AMORO_DEBUG_SUSPEND) \
		docker compose -f docker/kind/docker-compose.yml --profile dev up -d
	@echo "Attach IDE debugger to localhost:$(AMORO_DEBUG_PORT)"
	@echo "Follow progress: docker compose -f docker/local/docker-compose.yml logs -f"

stop-local:
	@echo "Stopping local debug environment..."
	@docker compose -f docker/local/docker-compose.yml down

start-deps:
	@echo "Starting local dependencies (Postgres & Minio)..."
	@docker compose -f docker/kind/docker-compose.yml --profile dev up -d

stop-deps:
	@echo "Stopping local dependencies (Postgres & Minio)..."
	@docker compose -f docker/kind/docker-compose.yml --profile dev down

prepare-debug-runtime:
	@echo "Cleaning up stale optimizer logs (prevents RAT license check failure)..."
	@rm -rf "$(AMORO_BIN_HOME)/logs/optimizer-local-test-"*
	@echo "Building and installing all modules to local Maven repo (~/.m2)..."
	@./mvnw clean install -DskipTests -Drat.skip=true -Dspotless.skip=true -Dcheckstyle.skip=true -B -ntp
	@$(MAKE) prepare-optimizer-lib

setup-debug-mode:
	@echo "Setting up debug mode (deps + build + install to ~/.m2 + lib sync)..."
	@$(MAKE) start-deps
	@$(MAKE) prepare-debug-runtime
	@echo ""
	@echo "Setup complete."
	@echo "Next: reload VS Code Java project, then run 'AmoroServiceContainer' from launch.json."

teardown-debug-mode:
	@echo "Tearing down debug mode (deps + extracted runtime cleanup)..."
	@$(MAKE) stop-deps
	@rm -rf "$(AMORO_RUNTIME_HOME)"
	@echo "Teardown complete."

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
	@echo "Synced optimizer libs to: $(AMORO_BIN_HOME)/lib"

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

teardown:
	@echo "Removing Kind clusters..."
	@kind delete cluster --name $(KIND_CLUSTER) 2>/dev/null 
	@kind delete cluster --name fusion-spark-cluster 2>/dev/null
	@echo "Removing Docker services and volumes..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml down -v
	@echo "Teardown complete."

status:
	@echo "=== Kind Cluster ==="
	@kind get clusters 2>/dev/null || echo "  No clusters"
	@echo ""
	@echo "=== Kubernetes Nodes ==="
	@$(KUBECTL) get nodes 2>/dev/null || echo "  Cannot connect to cluster (run: make setup-fusion)"
	@echo ""
	@echo "=== Docker Services ==="
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml ps
	@echo ""
	@echo "=== Spark Pods ==="
	@$(KUBECTL) get pods -n spark 2>/dev/null || echo "  No pods or cannot connect"

alias:
	@$(KUBECTL) config set-context --current --namespace=spark
	@echo "alias k='kubectl'"
