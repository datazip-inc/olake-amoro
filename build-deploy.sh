#!/bin/bash
#
# Build and Deploy Script for Testing Logging Implementation (OF Repository)
#
# This script:
# 1. Builds Maven artifacts with the new logging code
# 2. Creates Docker images with timestamped tags
# 3. Updates docker-compose.yml to use new images
# 4. Loads images into kind cluster
# 5. Tears down and recreates the cluster for fresh testing
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VERSION_TAG="logging-${TIMESTAMP}"
FUSION_IMAGE="olakego/fusion:${VERSION_TAG}"
SPARK_IMAGE="olakego/fusion-spark:${VERSION_TAG}"
COMPOSE_FILE="local-test/docker-compose.yml"
PROJECT_DIR=$(pwd)

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Amoro Logging Implementation Build & Deploy${NC}"
echo -e "${BLUE}================================${NC}"
echo ""
echo "Version Tag: ${VERSION_TAG}"
echo "Fusion Image: ${FUSION_IMAGE}"
echo "Spark Image: ${SPARK_IMAGE}"
echo ""

# Step 1: Clean and Build Maven Project
echo -e "${GREEN}[1/7] Building Maven artifacts...${NC}"
./mvnw clean package -DskipTests -T 4 -Drat.skip=true -Dspotless.skip=true -Dcheckstyle.skip=true
if [ $? -ne 0 ]; then
    echo -e "${RED}Maven build failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Maven build completed${NC}"
echo ""

# Step 2: Build Fusion Docker Image
echo -e "${GREEN}[2/7] Building Fusion Docker image...${NC}"
docker build \
    --file docker/amoro/Dockerfile \
    --tag ${FUSION_IMAGE} \
    --tag olakego/fusion:latest \
    .
if [ $? -ne 0 ]; then
    echo -e "${RED}Fusion Docker build failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Fusion image built: ${FUSION_IMAGE}${NC}"
echo ""

# Step 3: Build Spark Optimizer Docker Image
echo -e "${GREEN}[3/7] Building Spark Optimizer Docker image...${NC}"

# Find the optimizer-spark JAR
OPTIMIZER_JAR=$(find amoro-optimizer/amoro-optimizer-spark/target -name "*jar-with-dependencies.jar" | head -n 1)
if [ -z "$OPTIMIZER_JAR" ]; then
    echo -e "${RED}Optimizer JAR not found!${NC}"
    exit 1
fi

echo "  Using JAR: ${OPTIMIZER_JAR}"

# Copy JAR to docker context
cp "${OPTIMIZER_JAR}" docker/optimizer-spark/optimizer-job.jar

docker build \
    --file docker/optimizer-spark/Dockerfile \
    --tag ${SPARK_IMAGE} \
    --tag olakego/fusion-spark:latest \
    --build-arg OPTIMIZER_JOB=optimizer-job.jar \
    docker/optimizer-spark/

if [ $? -ne 0 ]; then
    echo -e "${RED}Spark Docker build failed!${NC}"
    exit 1
fi

# Cleanup
rm -f docker/optimizer-spark/optimizer-job.jar

echo -e "${GREEN}✓ Spark image built: ${SPARK_IMAGE}${NC}"
echo ""

# Step 4: Update docker-compose.yml
echo -e "${GREEN}[4/7] Updating docker-compose.yml...${NC}"

# Backup original
cp ${COMPOSE_FILE} ${COMPOSE_FILE}.backup

# Update Fusion image references
sed -i.tmp "s|image: olakego/fusion:.*|image: ${FUSION_IMAGE}|g" ${COMPOSE_FILE}
sed -i.tmp "s|image: olakego/fusion-spark:.*|image: ${SPARK_IMAGE}|g" ${COMPOSE_FILE}

rm -f ${COMPOSE_FILE}.tmp

echo -e "${GREEN}✓ docker-compose.yml updated${NC}"
echo ""

# Step 5: Teardown existing cluster
echo -e "${YELLOW}[5/7] Tearing down existing cluster...${NC}"
echo "This will:"
echo "  - Delete kind clusters (fusion-cluster, fusion-spark-cluster)"
echo "  - Stop Docker services"
echo "  - Remove volumes"
echo ""
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Aborted by user${NC}"
    # Restore backup
    mv ${COMPOSE_FILE}.backup ${COMPOSE_FILE}
    exit 1
fi

make clean-fusion-docker

echo -e "${GREEN}✓ Cluster torn down${NC}"
echo ""

# Step 6: Load images into kind and setup cluster
echo -e "${GREEN}[6/7] Setting up fresh cluster with new images...${NC}"
echo "Starting cluster..."
echo ""

make start-fusion-docker

echo -e "${GREEN}✓ Cluster setup initiated${NC}"
echo ""

# Wait for cluster to be ready
echo -e "${YELLOW}Waiting for cluster to be ready (this may take 2-3 minutes)...${NC}"
sleep 30

# Step 7: Verification instructions
echo ""
echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}Build & Deploy Complete! ✓${NC}"
echo -e "${GREEN}================================${NC}"
echo ""
echo "Images built:"
echo "  - ${FUSION_IMAGE}"
echo "  - ${SPARK_IMAGE}"
echo ""
echo "Next steps to verify logging:"
echo ""
echo "1. Wait for services to be fully ready (check logs):"
echo "   ${BLUE}docker logs olake-fusion -f${NC}"
echo ""
echo "2. Access Fusion Web UI:"
echo "   ${BLUE}http://localhost:1630${NC} (admin / password)"
echo ""
echo "3. Create a test table and run compaction"
echo ""
echo "4. Check logs for process-id and task-id separation:"
echo "   ${BLUE}docker exec olake-fusion ls -la /usr/local/amoro/logs/compaction/${NC}"
echo ""
echo "5. Check specific process logs:"
echo "   ${BLUE}docker exec olake-fusion find /usr/local/amoro/logs/compaction -name '*.log'${NC}"
echo ""
echo "6. View a specific task log:"
echo "   ${BLUE}docker exec olake-fusion cat /usr/local/amoro/logs/compaction/process-*/task-*.log${NC}"
echo ""
echo "7. Check Spark executor logs (in Kubernetes):"
echo "   ${BLUE}kubectl --context kind-fusion-cluster get pods -n spark${NC}"
echo "   ${BLUE}kubectl --context kind-fusion-cluster logs <pod-name> -n spark${NC}"
echo ""
echo "8. Verify log pattern includes context (P:processId|T:taskId|Table:tableName):"
echo "   ${BLUE}docker exec olake-fusion tail -50 /usr/local/amoro/logs/compaction/process-*/task-*.log${NC}"
echo ""
echo "To restore original docker-compose.yml:"
echo "   ${BLUE}mv ${COMPOSE_FILE}.backup ${COMPOSE_FILE}${NC}"
echo ""
echo "To check cluster status:"
echo "   ${BLUE}docker ps${NC}"
echo "   ${BLUE}kubectl --context kind-fusion-cluster get pods -A${NC}"
echo ""
echo -e "${YELLOW}Note: The cluster may take 2-3 minutes to be fully ready.${NC}"
echo -e "${YELLOW}Monitor Fusion service logs with: docker logs olake-fusion -f${NC}"
echo ""
echo -e "${BLUE}Logging Configuration:${NC}"
echo "  - Log4j2 configs: dist/src/main/amoro-bin/conf/optimize/log4j2-routing.xml"
echo "  - Executor config: amoro-optimizer/amoro-optimizer-spark/src/main/resources/log4j2-spark-executor.xml"
echo "  - Documentation: AMORO_TASK_LOGGING.md"
echo ""
