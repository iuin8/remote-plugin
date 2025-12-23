#!/bin/bash

# Gradle Remote Plugin Test Automation Script

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "🚀 Starting Gradle Remote Plugin Verification..."

# Change to project root directory
cd "$(dirname "$0")/../.." || exit 1
echo "📂 Working directory: $(pwd)"

# 1. Compile the plugin
echo -e "\n📦 ${GREEN}Step 1: Compiling Plugin...${NC}"
./gradlew :remote-plugin:compileKotlin -p consumer-gradle6-sample
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Compilation failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Compilation successful.${NC}"

# Define tasks to test
TASKS=(
    ":app:dev_publish"
    ":app:dev_restart"
    ":app:dev_debug"
    ":app:dev_jenkins_build"
)

# Run tasks
for task in "${TASKS[@]}"; do
    echo -e "\n🏃 ${GREEN}Running task: $task --info${NC}"
    ./gradlew $task --info -p consumer-gradle6-sample
    
    # We expect failures due to mock server settings, so we just check if it ran the logic
    # The primary goal is to ensure the [cmd] output is correct in the logs
    echo -e "🏁 Task $task execution finished."
done

echo -e "\n✨ ${GREEN}Verification script completed.${NC}"
echo "Please review the [cmd] logs above to ensure placeholders, TTY flags, and user wrapping are correct."
