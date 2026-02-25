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

# Makefile for Fusion + Kind (Spark on Kubernetes)
#
# Usage:
#   make setup-fusion  - One command to start everything (Kind + Docker services + optimizer)
#   make stop-fusion   - Stop Docker services (Kind cluster persists)
#   make debug-fusion  - Start everything with AMS JDWP debugger enabled
#   make teardown     - Remove everything (Kind cluster + Docker services + volumes)
#   make status       - Show status of cluster and Fusion

COMPOSE_DIR := docker/kind
KIND_CLUSTER := fusion-cluster
KUBECTL := kubectl --context kind-$(KIND_CLUSTER)
AMORO_DEBUG_PORT ?= 5005
AMORO_DEBUG_SUSPEND ?= n
AMORO_DIST_TAR := $(CURDIR)/dist/target/apache-amoro-0.9-SNAPSHOT-bin.tar.gz
AMORO_RUNTIME_HOME := $(CURDIR)/dist/target/amoro-0.9-SNAPSHOT

.PHONY: setup-fusion stop-fusion restart-fusion debug-fusion teardown logs status pods shell alias help debug-local stop-local start-deps stop-deps ams-debug ams-start ams-dist

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
