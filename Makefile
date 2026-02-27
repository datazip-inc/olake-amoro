# Makefile for Fusion + Kind (Spark on Kubernetes)
#
# Usage:
#   make setup-fusion  - One command to start everything (Kind + Docker services + optimizer)
#   make stop-fusion   - Stop Docker services (Kind cluster persists)
#   make teardown     - Remove everything (Kind cluster + Docker services + volumes)
#   make status       - Show status of cluster and Fusion

COMPOSE_DIR := docker/kind
KIND_CLUSTER := fusion-cluster
KUBECTL := kubectl --context kind-$(KIND_CLUSTER)

.PHONY: setup-fusion stop-fusion teardown logs status pods shell alias help

# Default target
.DEFAULT_GOAL := help

help:
	@echo "Fusion + Kind (Spark on Kubernetes)"
	@echo ""
	@echo "Usage:"
	@echo "  make setup-fusion   Start everything (Kind cluster + all services + optimizer)"
	@echo "  make stop-fusion    Stop Docker services (Kind cluster persists)"
	@echo "  make restart-fusion Restart Docker services"
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

